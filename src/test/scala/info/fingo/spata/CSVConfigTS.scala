/*
 * Copyright 2020 FINGO sp. z o.o.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package info.fingo.spata

import info.fingo.spata.io.reader

import scala.io.Source
import org.scalatest.funsuite.AnyFunSuite

class CSVConfigTS extends AnyFunSuite {

  test("Config should be build correctly") {
    val config = CSVConfig().fieldDelimiter(';').noHeader().fieldSizeLimit(100)
    val expected = CSVConfig(';', '\n', '"', hasHeader = false, PartialFunction.empty, Some(100))
    assert(config == expected)
  }

  test("Config should allow parser creation with proper settings") {
    val rs = 0x1E.toChar
    val content = s"'value 1A'|'value ''1B'$rs'value 2A'|'value ''2B'"
    val config = CSVConfig().fieldDelimiter('|').quoteMark('\'').recordDelimiter(rs).noHeader()
    val data = reader(Source.fromString(content))
    val parser = config.get
    val result = parser.get(data).unsafeRunSync()
    assert(result.length == 2)
    assert(result.head.size == 2)
    assert(result.head(1) == "value '1B")
  }
}
