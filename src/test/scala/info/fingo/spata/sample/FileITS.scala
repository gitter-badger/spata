package info.fingo.spata.sample

import java.io.FileWriter
import cats.effect.IO
import fs2.Stream
import org.scalatest.funsuite.AnyFunSuite
import info.fingo.spata.CSVReader

/* Samples which output processing results to another CSV file */
class FileITS extends AnyFunSuite {

  test("spata allows data conversion to another file") {
    case class DTV(day: String, tempVar: Double) // diurnal temperature variation
    val reader = CSVReader.config.get // reader with default configuration
    // get stream of CSV records while ensuring source cleanup
    val records = Stream
      .bracket(IO { SampleTH.sourceFromResource(SampleTH.dataFile) })(source => IO { source.close() })
      .flatMap(reader.parse)
    // convert and aggregate data, get stream of YTs
    val dtvs = records.filter { record =>
      record("max_temp") != "NaN" && record("min_temp") != "NaN"
    }.map { record =>
      val day = record("sol")
      val max = record.get[Double]("max_temp")
      val min = record.get[Double]("min_temp")
      DTV(day, max - min)
    }
    // write data to output file
    val outFile = SampleTH.getTempFile
    val output = Stream
      .bracket(IO { new FileWriter(outFile) })(writer => IO { writer.close() })
      .flatMap { writer =>
        dtvs.evalMap { dtv =>
          IO(writer.write(s"${dtv.day},${dtv.tempVar}\n"))
        }
      }
      .handleErrorWith { ex =>
        println(s"Error while converting data from ${SampleTH.dataFile} to ${outFile.getPath}: ${ex.getMessage}")
        Stream.empty[IO]
      }
    // assert result
    val check = Stream.eval_(IO(assert(outFile.length > 16000)))
    // run
    output.append(check).compile.drain.unsafeRunSync()
  }
}
