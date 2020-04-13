package info.fingo.spata

import cats.effect.IO
import fs2.Stream
import info.fingo.spata.parser.RecordParser._

/* Intermediate entity used to convert raw records into key-values indexed by header.
 * It converts additionally CSV parsing failures into stream error by raising CSVException.
 */
private[spata] class CSVContent private (
  data: Stream[IO, ParsingResult],
  index: Map[String, Int],
  hasHeader: Boolean = true
) {
  private val reverseIndex = index.map(x => x._2 -> x._1)

  /* Converts RawRecord into CSVRecord and raise ParsingFailure as CSVException */
  def toRecords: Stream[IO, CSVRecord] = data.map(wrapRecord).rethrow

  private def wrapRecord(pr: ParsingResult): Either[CSVException, CSVRecord] = pr match {
    case RawRecord(fields, location, recordNum) =>
      CSVRecord(fields, location.line, recordNum - dataOffset)(index)
    case ParsingFailure(code, location, recordNum, fieldNum) =>
      Left(
        new CSVException(
          code.message,
          code.toString,
          location.line,
          recordNum - dataOffset,
          location.position,
          reverseIndex.get(fieldNum - 1)
        )
      )
  }

  /* First data record should be always at row 1, so record num has to be adjusted if header is present. */
  private def dataOffset: Int = if (hasHeader) 1 else 0
}

/* CSVContent helper object. Used to create content for header and header-less data. */
private[spata] object CSVContent {

  /* Creates CSVContent for data with header. May return CSVException if no header is available (means empty source). */
  def apply(header: ParsingResult, data: Stream[IO, ParsingResult]): Either[CSVException, CSVContent] =
    buildHeaderIndex(header) match {
      case Right(index) => Right(new CSVContent(data, index))
      case Left(e) => Left(e)
    }

  /* Creates CSVContent for data without header - builds a numeric header. */
  def apply(headerSize: Int, data: Stream[IO, ParsingResult]): Either[CSVException, CSVContent] =
    Right(new CSVContent(data, buildNumHeader(headerSize), false))

  private def buildHeaderIndex(pr: ParsingResult): Either[CSVException, Map[String, Int]] = pr match {
    case RawRecord(captions, _, _) => Right(captions.zipWithIndex.toMap)
    case ParsingFailure(code, location, _, _) =>
      Left(new CSVException(code.message, code.toString, location.line, 0, location.position, None))
  }
  // tuple-style header: _1, _2, _3 etc.
  private def buildNumHeader(size: Int) = (0 until size).map(i => s"_${i + 1}" -> i).toMap
}
