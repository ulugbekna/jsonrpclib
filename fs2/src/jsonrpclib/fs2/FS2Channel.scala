package jsonrpclib
package fs2interop

import jsonrpclib.internals.MessageDispatcher

import fs2._
import jsonrpclib.internals._
import scala.util.Try
import cats.Monad
import cats.syntax.all._
import cats.effect.implicits._
import cats.effect.kernel._
import scala.util.Failure
import scala.util.Success
import cats.Applicative
import cats.data.Kleisli
import cats.MonadThrow
import jsonrpclib.StubTemplate.NotificationTemplate
import jsonrpclib.StubTemplate.RequestResponseTemplate
import cats.Defer
import jsonrpclib.internals.OutputMessage.ErrorMessage
import jsonrpclib.internals.OutputMessage.ResponseMessage
import cats.Functor
import cats.effect.std.syntax.supervisor
import cats.effect.std.Supervisor

trait FS2Channel[F[_]] extends Channel[F] {
  def withEndpoint(endpoint: Endpoint[F])(implicit F: Functor[F]): Resource[F, Unit] =
    Resource.make(mountEndpoint(endpoint))(_ => unmountEndpoint(endpoint.method))

  def withEndpoints(endpoint: Endpoint[F], rest: Endpoint[F]*)(implicit F: Monad[F]): Resource[F, Unit] =
    (endpoint :: rest.toList).traverse_(withEndpoint)
}

object FS2Channel {

  def apply[F[_]: Concurrent](
      inputStream: fs2.Stream[F, Payload],
      outputPipe: Payload => F[Unit]
  ): Resource[F, FS2Channel[F]] = {
    for {
      supervisor <- Supervisor[F]
      ref <- Ref[F].of(State[F](Map.empty, Map.empty, 0)).toResource
      impl = new Impl(outputPipe, ref, supervisor)
      _ <- inputStream.evalMap(impl.handleReceivedPayload).compile.drain.background
    } yield impl
  }

  private case class State[F[_]](
      pendingCalls: Map[CallId, OutputMessage => F[Unit]],
      endpoints: Map[String, Endpoint[F]],
      counter: Long
  ) {
    def nextCallId: (State[F], CallId) = (this.copy(counter = counter + 1), CallId.NumberId(counter))
    def storePendingCall(callId: CallId, handle: OutputMessage => F[Unit]): State[F] =
      this.copy(pendingCalls = pendingCalls + (callId -> handle))
    def removePendingCall(callId: CallId): (State[F], Option[OutputMessage => F[Unit]]) = {
      val result = pendingCalls.get(callId)
      (this.copy(pendingCalls = pendingCalls.removed(callId)), result)
    }
    def mountEndpoint(endpoint: Endpoint[F]): Either[ConflictingMethodError, State[F]] =
      endpoints.get(endpoint.method) match {
        case None    => Right(this.copy(endpoints = endpoints + (endpoint.method -> endpoint)))
        case Some(_) => Left(ConflictingMethodError(endpoint.method))
      }
    def removeEndpoint(method: String): State[F] =
      copy(endpoints = endpoints.removed(method))
  }

  private class Impl[F[_]](
      private val sink: Payload => F[Unit],
      private val state: Ref[F, FS2Channel.State[F]],
      supervisor: Supervisor[F]
  )(implicit F: Concurrent[F])
      extends MessageDispatcher[F]
      with FS2Channel[F] {

    def mountEndpoint(endpoint: Endpoint[F]): F[Unit] = state.modify(s =>
      s.mountEndpoint(endpoint) match {
        case Left(error)  => (s, MonadThrow[F].raiseError(error))
        case Right(value) => (value, Applicative[F].unit)
      }
    )
    def unmountEndpoint(method: String): F[Unit] = state.update(_.removeEndpoint(method))

    protected def background[A](fa: F[A]): F[Unit] = supervisor.supervise(fa).void
    protected def reportError(params: Option[Payload], error: ProtocolError, method: String): F[Unit] = ???
    protected def getEndpoint(method: String): F[Option[Endpoint[F]]] = state.get.map(_.endpoints.get(method))
    protected def sendMessage(message: Message): F[Unit] = sink(Codec.encodeBytes(message))
    protected def nextCallId(): F[CallId] = state.modify(_.nextCallId)
    protected def createPromise[A](): F[(Try[A] => F[Unit], () => F[A])] = Deferred[F, Try[A]].map { promise =>
      def compile(trya: Try[A]): F[Unit] = promise.complete(trya).void
      def get(): F[A] = promise.get.flatMap(_.liftTo[F])
      (compile(_), get)
    }
    protected def storePendingCall(callId: CallId, handle: OutputMessage => F[Unit]): F[Unit] =
      state.update(_.storePendingCall(callId, handle))
    protected def removePendingCall(callId: CallId): F[Option[OutputMessage => F[Unit]]] =
      state.modify(_.removePendingCall(callId))

  }
}
