package examples.server

import jsonrpclib.CallId
import jsonrpclib.fs2._
import cats.effect._
import fs2.io._
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import jsonrpclib.Endpoint
import cats.syntax.all._

object ServerMain extends IOApp.Simple {

  // Reserving a method for cancelation.
  val cancelEndpoint = CancelTemplate.make[CallId]("$/cancel", identity, identity)

  // Creating a datatype that'll serve as a request (and response) of an endpoint
  case class IntWrapper(value: Int)
  object IntWrapper {
    implicit val jcodec: JsonValueCodec[IntWrapper] = JsonCodecMaker.make
  }

  // Implementing an incrementation endpoint
  val increment = Endpoint[IO]("increment").simple { in: IntWrapper =>
    IO.consoleForIO.errorln(s"Server received $in") >>
      IO.pure(in.copy(value = in.value + 1))
  }

  def run: IO[Unit] = {
    // Using errorln as stdout is used by the RPC channel
    IO.consoleForIO.errorln("Starting server") >>
      FS2Channel[IO](cancelTemplate = Some(cancelEndpoint))
        .flatMap(_.withEndpointStream(increment)) // mounting an endpoint onto the channel
        .flatMap(channel =>
          fs2.Stream
            .eval(IO.never) // running the server forever
            .concurrently(stdin[IO](512).through(lsp.decodePayloads).through(channel.input))
            .concurrently(channel.output.through(lsp.encodePayloads).through(stdout[IO]))
        )
        .compile
        .drain
        .guarantee(IO.consoleForIO.errorln("Terminating server"))
  }

}
