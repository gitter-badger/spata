/*
 * Copyright 2020 FINGO sp. z o.o.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Part of this code (reader.WithBlocker.decode) is derived under Apache-2.0 license from http4s.
 * Copyright 2013-2020 http4s.org
 */
package info.fingo.spata.io

import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.io.{BufferedSource, Codec, Source}
import cats.effect.{Blocker, ContextShift, IO, Resource}
import fs2.{io, Chunk, Pipe, Pull, Stream}

/** Utility to read external data and provide stream of characters.
  * It may be used directly or through the provided inner class [[reader.WithBlocker]],
  * which supports context (thread) shifting for blocking operations
  * (see [[https://typelevel.org/cats-effect/concurrency/basics.html#blocking-threads Cats Effect concurrency guide]]).
  *
  * The reading functions in [[reader.WithBlocker]], except the one reading from [[scala.io.Source]],
  * use [[https://fs2.io/io.html fs2-io]] library and are generally more efficient than they "regular" counterparts.
  * On the contrary, the function reading from `Source`
  * [[reader.WithBlocker.read(source:scala\.io\.Source)* reader.withBlocker.read(source)]]
  * is much less efficient in most scenarios and should be chosen deliberately.
  *
  * In every case, the caller of function taking resource ([[scala.io.Source]] or `java.io.InputStream`) as a parameter
  * is responsible for its cleanup.
  *
  * Functions reading binary data (from `java.io.InputStream` or taking `java.nio.file.Path`)
  * use implicit [[scala.io.Codec]] to decode input data. If not provided, the default JVM charset is used.
  *
  * For input data encoded in `UTF`, the byte order mark (BOM) is removed automatically.
  * This is done even for functions reading from already decoded [[scala.io.Source]]
  * as long as the implicit [[scala.io.Codec]] with `UTF` charset is provided.
  *
  * All of above applies not only to `read` function but also to [[reader.apply]] and [[reader.by]],
  * which internally make use of `read`.
  */
object reader {

  private val blockSize = 4096
  private val autoClose = false
  private val bom = "\uFEFF".head
  private val UTFCharsetPrefix = "UTF-"

  /** Reads a CSV source and returns a stream of character.
    * The I/O operations are wrapped in [[cats.effect.IO]] allowing deferred computation.
    * The returned [[fs2.Stream]] allows further input processing in a very flexible, purely functional manner.
    *
    * The caller of this function is responsible for proper resource acquisition and release.
    * This is optimally done with [[fs2.Stream.bracket]], e.g.:
    * {{{
    * val stream = Stream
    *   .bracket(IO { Source.fromFile("input.csv") })(source => IO { source.close() })
    *   .flatMap(reader.read)
    * }}}
    *
    * I/O operations are executed on current thread, without execution context shifting.
    * To shift them to a blocking context, use [[withBlocker(blocker:cats\.effect\.Blocker)* shifting]].
    *
    * Processing I/O errors, manifested through [[java.io.IOException]],
    * should be handled with [[fs2.Stream.handleErrorWith]].
    * If not handled, they will propagate as exceptions.
    *
    * Character encoding has to be handled while creating [[scala.io.Source]].
    *
    * @param source the source containing CSV content
    * @return the stream of characters
    */
  def read(source: Source): Stream[IO, Char] =
    Stream.fromIterator[IO][Char](source).through(skipBom)

  /** Reads a CSV source and returns a stream of character.
    *
    * @note This function does not close provided input stream.
    *
    * @see [[read(source:scala\.io\.Source)* read]] for more information.
    *
    * @param is input stream containing CSV content
    * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
    * @return the stream of characters
    */
  def read(is: InputStream)(implicit codec: Codec): Stream[IO, Char] = read(new BufferedSource(is, blockSize))

  /** Reads a CSV file and returns a stream of character.
    *
    * @param path the path to source file
    * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
    * @return the stream of characters
    */
  def read(path: Path)(implicit codec: Codec): Stream[IO, Char] =
    Stream
      .bracket(IO {
        Source.fromInputStream(Files.newInputStream(path, StandardOpenOption.READ))
      })(source => IO { source.close() })
      .flatMap(reader.read)

  /** Alias for various `read` methods.
    *
    * @param cvs the CSV data
    * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
    * @tparam A type of source
    * @return the stream of characters
    */
  def apply[A: CSV](cvs: A)(implicit codec: Codec): Stream[IO, Char] = cvs match {
    case s: Source => read(s)
    case is: InputStream => read(is)
    case p: Path => read(p)
  }

  /** Pipe converting stream with CSV source to stream of characters.
    *
    * @example
    * {{{
    * val stream = Stream
    *   .bracket(IO { Source.fromFile("input.csv") })(source => IO { source.close() })
    *   .through(reader.by)
    * }}}
    *
    * @see [[read(source:scala\.io\.Source)* read]] for more information.
    *
    * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
    * @tparam A type oof source
    * @return a pipe to converter CSV source into [[scala.Char]]s
    */
  def by[A: CSV](implicit codec: Codec): Pipe[IO, A, Char] = _.flatMap(apply(_))

  /** Provides reader with support of context shifting for I/O operations.
    *
    * @param blocker an execution context to be used for blocking I/O operations
    * @param cs the default execution environment for non-blocking operation
    * @return reader with support for context shifting
    */
  def withBlocker(blocker: Blocker)(implicit cs: ContextShift[IO]): WithBlocker =
    new WithBlocker(Some(blocker))(cs)

  /** Provides reader with support of context shifting for I/O operations.
    * Uses internal, default blocker backed by a new cached thread pool.
    *
    * @param cs the default execution environment for non-blocking operation
    * @return reader with support for context shifting
    */
  def withBlocker(implicit cs: ContextShift[IO]): WithBlocker = new WithBlocker(None)(cs)

  /* Skip BOM from UTF encoded streams */
  private def skipBom(implicit codec: Codec): Pipe[IO, Char, Char] =
    stream =>
      if (codec.charSet.name.startsWith(UTFCharsetPrefix)) stream.dropWhile(_ == bom)
      else stream

  /** Reader which shifts I/O operations to a execution context that is safe to use for blocking operations.
    * If no blocker is provided, a new one, backed by a cached thread pool, is allocated.
    *
    * @param blocker optional execution context to be used for blocking I/O operations
    * @param cs the default execution environment for non-blocking operation
    */
  final class WithBlocker private[spata] (blocker: Option[Blocker])(implicit cs: ContextShift[IO]) {

    /** Reads a CSV source and returns a stream of character.
      *
      * I/O operations are shifted to an execution context provided by a [[cats.effect.Blocker]].
      *
      * @note This function is much less efficient for most use cases than its non-shifting counterpart,
      * [[reader.read(source:scala\.io\.Source)* reader.read]].
      * This is due to [[scala.io.Source]] character-based iterator,
      * which causes context shift for each fetched character.
      *
      * @see [[reader.read(source:scala\.io\.Source)* reader.read]] for more information.
      *
      * @param source the source containing CSV content
      * @return the stream of characters
      */
    def read(source: Source): Stream[IO, Char] =
      for {
        b <- Stream.resource(br)
        char <- Stream.fromBlockingIterator[IO][Char](b, source).through(reader.skipBom)
      } yield char

    /** Reads a CSV source and returns a stream of character.
      *
      * This function does not close the input stream after use, which is different from `fs2-io` default behavior.
      *
      * @see [[read(source:scala\.io\.Source)* read]] for more information.
      *
      * @param is input stream containing CSV content
      * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
      * @return the stream of characters
      */
    def read(is: InputStream)(implicit codec: Codec): Stream[IO, Char] =
      for {
        blocker <- Stream.resource(br)
        char <- io
          .readInputStream(IO(is), blockSize, blocker, autoClose)
          .through(byte2char)
      } yield char

    /** Reads a CSV file and returns a stream of character.
      *
      * @param path the path to source file
      * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
      * @return the stream of characters
      */
    def read(path: Path)(implicit codec: Codec): Stream[IO, Char] =
      for {
        blocker <- Stream.resource(br)
        char <- io.file
          .readAll[IO](path, blocker, blockSize)
          .through(byte2char)
      } yield char

    /** Alias for various `read` methods.
      *
      * @param cvs the CSV data
      * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
      * @tparam A type of source
      * @return the stream of characters
      */
    def apply[A: CSV](cvs: A)(implicit codec: Codec): Stream[IO, Char] = cvs match {
      case s: Source => read(s)
      case is: InputStream => read(is)
      case p: Path => read(p)
    }

    /** Pipe converting stream with CSV source to stream of characters.
      *
      * @see [[read(source:scala\.io\.Source)* read]] for more information.
      *
      * @param codec codec used to convert bytes to characters, with default JVM charset as fallback
      * @tparam A type of source
      * @return a pipe to converter CSV source into [[scala.Char]]s
      */
    def by[A: CSV](implicit codec: Codec): Pipe[IO, A, Char] = _.flatMap(apply(_))

    /* Wrap provided blocker in dummy-resource or get real resource with new blocker. */
    private def br = blocker.map(b => Resource(IO((b, IO.unit)))).getOrElse(Blocker[IO])

    private def byte2char(implicit codec: Codec): Pipe[IO, Byte, Char] =
      _.through(decode(codec)).through(reader.skipBom)

    /* Decode bytes to chars based on provided codec.
     * This function is ported from org.http4s.util.decode with slight modifications */
    private def decode(codec: Codec): Pipe[IO, Byte, Char] = {
      val decoder = codec.charSet.newDecoder
      val maxCharsPerByte = decoder.maxCharsPerByte().ceil.toInt
      val avgBytesPerChar = (1.0 / decoder.averageCharsPerByte()).ceil.toInt
      val charBufferSize = 128

      def cb2cc(cb: CharBuffer): Chunk[Char] = Chunk.chars(cb.flip.toString.toCharArray)

      _.repeatPull[Char] {
        _.unconsN(charBufferSize * avgBytesPerChar, allowFewer = true).flatMap {
          case Some((chunk, stream)) if chunk.nonEmpty =>
            val bytes = chunk.toArray
            val bb = ByteBuffer.wrap(bytes)
            val cb = CharBuffer.allocate(bytes.length * maxCharsPerByte)
            val cr = decoder.decode(bb, cb, false)
            if (cr.isError) Pull.raiseError[IO](new CharacterCodingException)
            else {
              val nextStream = stream.consChunk(Chunk.byteBuffer(bb.slice()))
              Pull.output(cb2cc(cb)).as(Some(nextStream))
            }
          case Some((_, stream)) =>
            Pull.output(Chunk.empty[Char]).as(Some(stream))
          case None =>
            val cb = CharBuffer.allocate(1)
            val cr = decoder.decode(ByteBuffer.allocate(0), cb, true)
            if (cr.isError) Pull.raiseError[IO](new CharacterCodingException)
            else {
              decoder.flush(cb)
              Pull.output(cb2cc(cb)).as(None)
            }
        }
      }
    }
  }

  /** Representation of CSV data source, used to witness that certain sources may be used by read operations.
    * @see [[CSV$ CSV]] object.
    */
  sealed trait CSV[-A]

  /** Implicits to witness that given type is supported by reader as CSV source */
  object CSV {

    /** Witness that [[scala.io.Source]] may be used with reader functions */
    implicit object sourceWitness extends CSV[Source]

    /** Witness that [[java.io.InputStream]] may be used with reader functions */
    implicit object inputStreamWitness extends CSV[InputStream]

    /** Witness that [[java.nio.file.Path]] may be used with reader functions */
    implicit object pathWitness extends CSV[Path]

  }
}
