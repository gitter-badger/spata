/*
 * Copyright 2020 FINGO sp. z o.o.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package info.fingo.spata.parser

import cats.effect.IO
import fs2.{Pipe, Pull, Stream}
import ParsingErrorCode._

/* A finite-state transducer to converter plain source characters into context-dependent symbols,
 * taking into consideration special meaning of some characters (e.g. separators), quoting and escaping.
 */
private[spata] class CharParser(fieldDelimiter: Char, recordDelimiter: Char, quote: Char) {
  import CharParser._
  import CharParser.CharPosition._

  /* Transforms plain characters into context-dependent symbols by providing FS2 pipe. */
  def toCharResults: Pipe[IO, Char, CharResult] = toCharResults(CharState(Left(STX), Start))

  private def toCharResults(state: CharState): Pipe[IO, Char, CharResult] = {
    def loop(chars: Stream[IO, Char], state: CharState): Pull[IO, CharResult, Unit] =
      chars.pull.uncons1.flatMap {
        case Some((h, t)) =>
          parseChar(h, state) match {
            case cs: CharState => Pull.output1(cs) >> loop(t, cs)
            case cf: CharFailure => Pull.output1(cf) >> Pull.done
          }
        case None => Pull.output1(endOfStream(state)) >> Pull.done
      }
    chars => loop(chars, state).stream
  }

  private def endOfStream(state: CharState): CharResult =
    state.position match {
      case Quoted => CharFailure(UnmatchedQuotation)
      case _ => CharState(Left(ETX), FinishedRecord)
    }

  @inline
  private def isDelimiter(c: Char): Boolean = c == fieldDelimiter || c == recordDelimiter

  /* Core translating function - state transitions. */
  private def parseChar(char: Char, state: CharState): CharResult =
    char match {
      case `quote` if state.atBeginning => CharState(Left(char), Quoted)
      case `quote` if state.position == Quoted => CharState(Left(char), Escape)
      case `quote` if state.position == Escape => CharState(Right(quote), Quoted)
      case `quote` => CharFailure(UnclosedQuotation)
      case CR if recordDelimiter == LF && state.position != Quoted => CharState(Left(char), state.position)
      case c if isDelimiter(c) && state.position == Quoted => CharState(Right(c), Quoted)
      case `fieldDelimiter` => CharState(Left(char), FinishedField)
      case `recordDelimiter` => CharState(Left(char), FinishedRecord)
      case c if c.isWhitespace && state.atBoundary => CharState(Left(char), state.position)
      case c if c.isWhitespace && state.position == FinishedField => CharState(Left(char), Start)
      case c if c.isWhitespace && state.position == Escape => CharState(Left(char), End)
      case c if c.isWhitespace && state.isSimple => CharState(Right(c), Trailing)
      case _ if state.position == Escape || state.position == End => CharFailure(UnescapedQuotation)
      case c if state.atBeginning => CharState(Right(c), Regular)
      case c if state.position == Trailing => CharState(Right(c), Regular)
      case c => CharState(Right(c), state.position)
    }

  val newLineDelimiter: Option[Char] = List(recordDelimiter, fieldDelimiter, quote).find(_ == LF)
}

private[spata] object CharParser {
  val LF: Char = 0x0A.toChar
  val CR: Char = 0x0D.toChar
  val STX: Char = 0x02.toChar
  val ETX: Char = 0x03.toChar

  object CharPosition extends Enumeration {
    type CharPosition = Value
    val Start, Regular, Quoted, Trailing, Escape, End, FinishedField, FinishedRecord = Value
  }
  import CharPosition._

  sealed trait CharResult

  case class CharFailure(code: ErrorCode) extends CharResult

  /* Represents character with its context - position in CSV.
   * If character retains its direct meaning it is represented as Right.
   * If it is interpreted as a special character or is skipped, it is represented as Left.
   */
  case class CharState(char: Either[Char, Char], position: CharPosition) extends CharResult {
    def isSimple: Boolean = position == Regular || position == Trailing
    def finished: Boolean = position == FinishedField || position == FinishedRecord
    def atBoundary: Boolean = position == Start || position == End
    def atBeginning: Boolean = position == Start || finished
    def isNewLine: Boolean = char.fold(_ == LF, _ == LF)
    def hasChar: Boolean = char.isRight
  }
}
