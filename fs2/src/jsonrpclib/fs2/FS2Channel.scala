package jsonrpclib
package fs2

import _root_.fs2.Pipe
import _root_.fs2.Stream
import cats.Applicative
import cats.Functor
import cats.Monad
import cats.MonadThrow
import cats.effect.Fiber
import cats.effect.kernel._
import cats.effect.std.Supervisor
import cats.syntax.all._
import cats.effect.syntax.all._
import jsonrpclib.internals.MessageDispatcher
import jsonrpclib.internals._

import scala.util.Try
import _root_.fs2.concurrent.SignallingRef

trait FS2Channel[F[_]] extends Channel[F] {
  def withEndpoint(endpoint: Endpoint[F])(implicit F: Functor[F]): Resource[F, FS2Channel[F]] =
    Resource.make(mountEndpoint(endpoint))(_ => unmountEndpoint(endpoint.method)).map(_ => this)

  def withEndpointStream(endpoint: Endpoint[F])(implicit F: MonadCancelThrow[F]): Stream[F, FS2Channel[F]] =
    Stream.resource(withEndpoint(endpoint))

  def withEndpoints(endpoint: Endpoint[F], rest: Endpoint[F]*)(implicit F: Monad[F]): Resource[F, FS2Channel[F]] =
    (endpoint :: rest.toList).traverse_(withEndpoint).as(this)

  def withEndpointStream(endpoint: Endpoint[F], rest: Endpoint[F]*)(implicit
      F: MonadCancelThrow[F]
  ): Stream[F, FS2Channel[F]] =
    Stream.resource(withEndpoints(endpoint, rest: _*))

  def open: Resource[F, Unit]
  def openStream: Stream[F, Unit]
  def openStreamForever: Stream[F, Nothing]
}

object FS2Channel {

  def lspCompliant[F[_]: Concurrent](
      byteStream: Stream[F, Byte],
      byteSink: Pipe[F, Byte, Unit],
      bufferSize: Int = 512,
      maybeCancelTemplate: Option[CancelTemplate] = None
  ): Stream[F, FS2Channel[F]] = internals.LSP.writeSink(byteSink, bufferSize).flatMap { sink =>
    apply[F](internals.LSP.readStream(byteStream), sink, maybeCancelTemplate)
  }

  def apply[F[_]: Concurrent](
      payloadStream: Stream[F, Payload],
      payloadSink: Payload => F[Unit],
      maybeCancelTemplate: Option[CancelTemplate] = None
  ): Stream[F, FS2Channel[F]] = {
    for {
      supervisor <- Stream.resource(Supervisor[F])
      ref <- Ref[F].of(State[F](Map.empty, Map.empty, Map.empty, 0)).toStream
      isOpen <- SignallingRef[F].of(false).toStream
      awaitingSink = isOpen.waitUntil(identity) >> payloadSink(_: Payload)
      impl = new Impl(awaitingSink, ref, isOpen, supervisor, maybeCancelTemplate)

      // Creating a bespoke endpoint to receive cancelation requests
      maybeCancelEndpoint: Option[Endpoint[F]] = maybeCancelTemplate.map { cancelTemplate =>
        implicit val codec: Codec[cancelTemplate.C] = cancelTemplate.codec
        Endpoint[F](cancelTemplate.method).notification[cancelTemplate.C] { request =>
          val callId = cancelTemplate.toCallId(request)
          impl.cancel(callId)
        }
      }
      // mounting the cancelation endpoint
      _ <- maybeCancelEndpoint.traverse_(ep => impl.mountEndpoint(ep)).toStream
      _ <- Stream(()).concurrently {
        // Gatekeeping the pull until the channel is actually marked as open
        payloadStream.pauseWhen(isOpen.map(b => !b)).evalMap(impl.handleReceivedPayload)
      }
    } yield impl
  }

  private case class State[F[_]](
      runningCalls: Map[CallId, Fiber[F, Throwable, Unit]],
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

    def addRunningCall(callId: CallId, fiber: Fiber[F, Throwable, Unit]): State[F] =
      copy(runningCalls = runningCalls + (callId -> fiber))

    def removeRunningCall(callId: CallId): State[F] =
      copy(runningCalls = runningCalls - callId)
  }

  private class Impl[F[_]](
      private val sink: Payload => F[Unit],
      private val state: Ref[F, FS2Channel.State[F]],
      private val isOpen: SignallingRef[F, Boolean],
      supervisor: Supervisor[F],
      maybeCancelTemplate: Option[CancelTemplate]
  )(implicit F: Concurrent[F])
      extends MessageDispatcher[F]
      with FS2Channel[F] {

    def mountEndpoint(endpoint: Endpoint[F]): F[Unit] = state
      .modify(s =>
        s.mountEndpoint(endpoint) match {
          case Left(error)  => (s, MonadThrow[F].raiseError[Unit](error))
          case Right(value) => (value, Applicative[F].unit)
        }
      )
      .flatMap(identity)

    def unmountEndpoint(method: String): F[Unit] = state.update(_.removeEndpoint(method))

    def open: Resource[F, Unit] = Resource.make[F, Unit](isOpen.set(true))(_ => isOpen.set(false))
    def openStream: Stream[F, Unit] = Stream.resource(open)
    def openStreamForever: Stream[F, Nothing] = openStream.evalMap(_ => F.never)

    protected[fs2] def cancel(callId: CallId): F[Unit] = state.get.map(_.runningCalls.get(callId)).flatMap {
      case None        => F.unit
      case Some(fiber) => fiber.cancel
    }

    protected def background[A](maybeCallId: Option[CallId], fa: F[A]): F[Unit] =
      maybeCallId match {
        case None => supervisor.supervise(fa).void
        case Some(callId) =>
          val runAndClean = fa.void.guarantee(state.update(_.removeRunningCall(callId)))
          supervisor.supervise(runAndClean).flatMap { fiber =>
            state.update(_.addRunningCall(callId, fiber))
          }
      }
    protected def reportError(params: Option[Payload], error: ProtocolError, method: String): F[Unit] = ???
    protected def getEndpoint(method: String): F[Option[Endpoint[F]]] = state.get.map(_.endpoints.get(method))
    protected def sendMessage(message: Message): F[Unit] = sink(Codec.encode(message))
    protected def nextCallId(): F[CallId] = state.modify(_.nextCallId)
    protected def createPromise[A](callId: CallId): F[(Try[A] => F[Unit], () => F[A])] = Deferred[F, Try[A]].map {
      promise =>
        def compile(trya: Try[A]): F[Unit] = promise.complete(trya).void
        def get(): F[A] = promise.get.flatMap(_.liftTo[F])
        (compile(_), () => get().onCancel(cancelRequest(callId)))
    }
    protected def storePendingCall(callId: CallId, handle: OutputMessage => F[Unit]): F[Unit] =
      state.update(_.storePendingCall(callId, handle))
    protected def removePendingCall(callId: CallId): F[Option[OutputMessage => F[Unit]]] =
      state.modify(_.removePendingCall(callId))

    private val cancelRequest: CallId => F[Unit] = maybeCancelTemplate
      .map { cancelTemplate =>
        val stub = notificationStub(cancelTemplate.method)(cancelTemplate.codec)
        stub.compose(cancelTemplate.fromCallId(_))
      }
      .getOrElse((_: CallId) => F.unit)
  }
}
