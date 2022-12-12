/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.util

import java.nio.charset.StandardCharsets.US_ASCII
import java.util.UUID

import scala.util.Random

import org.apache.spark.SparkFunSuite

class CodecSuite extends SparkFunSuite {

  import CodecSuite._

  // Z85 reference strings are generated by https://cryptii.com/pipes/z85-encoder
  val testUuids = Seq[(UUID, String)](
    new UUID(0L, 0L) -> "00000000000000000000",
    new UUID(Long.MinValue, Long.MinValue) -> "Fb/MH00000Fb/MH00000",
    new UUID(-1L, -1L) -> "%nSc0%nSc0%nSc0%nSc0",
    new UUID(0L, Long.MinValue) -> "0000000000Fb/MH00000",
    new UUID(0L, -1L) -> "0000000000%nSc0%nSc0",
    new UUID(0L, Long.MaxValue) -> "0000000000Fb/MG%nSc0",
    new UUID(Long.MinValue, 0L) -> "Fb/MH000000000000000",
    new UUID(-1L, 0L) -> "%nSc0%nSc00000000000",
    new UUID(Long.MaxValue, 0L) -> "Fb/MG%nSc00000000000",
    new UUID(0L, 1L) -> "00000000000000000001",
    // Just a few random ones, using literals for test determinism
    new UUID(-4124158004264678669L, -6032951921472435211L) -> "-(5oirYA.yTvx6v@H:L>",
    new UUID(6453181356142382984L, 8208554093199893996L) -> "s=Mlx-0Pp@AQ6uw@k6=D",
    new UUID(6453181356142382984L, -8208554093199893996L) -> "s=Mlx-0Pp@JUL=R13LuL",
    new UUID(-4124158004264678669L, 8208554093199893996L) -> "-(5oirYA.yAQ6uw@k6=D")

  // From https://rfc.zeromq.org/spec/32/ - Test Case
  test("Z85 spec reference value") {
    val inputBytes: Array[Byte] =
      Array(0x86, 0x4F, 0xD2, 0x6F, 0xB5, 0x59, 0xF7, 0x5B).map(_.toByte)
    val expectedEncodedString = "HelloWorld"
    val actualEncodedString = Codec.Base85Codec.encodeBytes(inputBytes)
    assert(actualEncodedString === expectedEncodedString)
    val outputBytes = Codec.Base85Codec.decodeAlignedBytes(actualEncodedString)
    assert(outputBytes sameElements inputBytes)
  }

  test("Z85 reference implementation values") {
    for ((id, expectedEncodedString) <- testUuids) {
      val actualEncodedString = Codec.Base85Codec.encodeUUID(id)
      assert(actualEncodedString === expectedEncodedString)
    }
  }

  test("Z85 spec character map") {
    assert(Codec.Base85Codec.ENCODE_MAP.length === 85)
    val referenceBytes = Seq(
      0x00, 0x09, 0x98, 0x62, 0x0f, 0xc7, 0x99, 0x43, 0x1f, 0x85,
      0x9a, 0x24, 0x2f, 0x43, 0x9b, 0x05, 0x3f, 0x01, 0x9b, 0xe6,
      0x4e, 0xbf, 0x9c, 0xc7, 0x5e, 0x7d, 0x9d, 0xa8, 0x6e, 0x3b,
      0x9e, 0x89, 0x7d, 0xf9, 0x9f, 0x6a, 0x8d, 0xb7, 0xa0, 0x4b,
      0x9d, 0x75, 0xa1, 0x2c, 0xad, 0x33, 0xa2, 0x0d, 0xbc, 0xf1,
      0xa2, 0xee, 0xcc, 0xaf, 0xa3, 0xcf, 0xdc, 0x6d, 0xa4, 0xb0,
      0xec, 0x2b, 0xa5, 0x91, 0xfb, 0xe9, 0xa6, 0x72)
      .map(_.toByte).toArray
    val referenceString = new String(Codec.Base85Codec.ENCODE_MAP, US_ASCII)
    val encodedString = Codec.Base85Codec.encodeBytes(referenceBytes)
    assert(encodedString === referenceString)
    val decodedBytes = Codec.Base85Codec.decodeAlignedBytes(encodedString)
    assert(decodedBytes sameElements referenceBytes)
  }

  test("Reject illegal Z85 input - unaligned string") {
    // Minimum string should 5 characters
    val illegalEncodedString = "abc"
    assertThrows[IllegalArgumentException] {
      Codec.Base85Codec.decodeBytes(
        illegalEncodedString,
        // This value is irrelevant, any value should cause the failure.
        outputLength = 3)
    }
  }

  // scalastyle:off nonascii
  test(s"Reject illegal Z85 input - illegal character") {
    for (char <- Seq[Char]('î', 'π', '"', 0x7F)) {
      val illegalEncodedString = String.valueOf(Array[Char]('a', 'b', char, 'd', 'e'))
      val ex = intercept[IllegalArgumentException] {
        Codec.Base85Codec.decodeAlignedBytes(illegalEncodedString)
      }
      assert(ex.getMessage.contains("Input is not valid Z85"))
    }
  }
  // scalastyle:on nonascii

  test("base85 codec uuid roundtrips") {
    for ((id, _) <- testUuids) {
      val encodedString = Codec.Base85Codec.encodeUUID(id)
      // 16 bytes always get encoded into 20 bytes with Base85.
      assert(encodedString.length === Codec.Base85Codec.ENCODED_UUID_LENGTH)
      val decodedId = Codec.Base85Codec.decodeUUID(encodedString)
      assert(id === decodedId, s"encodedString = $encodedString")
    }
  }

  test("base85 codec empty byte array") {
    val empty = Array.empty[Byte]
    val encodedString = Codec.Base85Codec.encodeBytes(empty)
    assert(encodedString === "")
    val decodedArray = Codec.Base85Codec.decodeAlignedBytes(encodedString)
    assert(decodedArray.isEmpty)
    val decodedArray2 = Codec.Base85Codec.decodeBytes(encodedString, 0)
    assert(decodedArray2.isEmpty)
  }

  test("base85 codec byte array random roundtrips") {
    val rand = new Random(1L) // Fixed seed for determinism
    val arrayLengths = (1 to 20) ++ Seq(32, 56, 64, 128, 1022, 11 * 1024 * 1024)

    for (len <- arrayLengths) {
      val inputArray: Array[Byte] = Array.ofDim(len)
      rand.nextBytes(inputArray)
      val encodedString = Codec.Base85Codec.encodeBytes(inputArray)
      val decodedArray = Codec.Base85Codec.decodeBytes(encodedString, len)
      assert(decodedArray === inputArray, s"encodedString = $encodedString")
    }
  }

  /**
   * Execute `thunk` works for strings containing any of the possible base85 characters at either
   * beginning, middle, or end positions.
   */
  private def forAllEncodedStrings(thunk: String => Unit): Unit = {
    // Basically test that every possible character can occur at any
    // position with a 20 character string.
    val characterString = new String(Codec.Base85Codec.ENCODE_MAP, US_ASCII)
    // Use this to fill in the remaining 17 characters.
    val fillerChar = "x"

    var count = 0
    for {
      firstChar <- characterString
      middleChar <- characterString
      finalChar <- characterString
    } {
      val sb = new StringBuilder
      sb += firstChar
      sb ++= fillerChar * 9
      sb += middleChar
      sb ++= fillerChar * 8
      sb += finalChar
      val encodedString = sb.toString()
      assert(encodedString.length === 20)
      thunk(encodedString)
      count += 1
    }
    assert(count === 85 * 85 * 85)
  }

  test("base85 character set is JSON-safe") {
    forAllEncodedStrings { inputString =>
      val inputObject = JsonRoundTripContainer(inputString)
      val jsonString = JsonUtils.toJson(inputObject)
      assert(jsonString.contains(inputString),
        "Some character from the input had to be escaped to be JSON-safe:" +
          s"input = '$inputString' vs JSON = '$jsonString'")
      val outputObject = JsonUtils.fromJson[JsonRoundTripContainer](jsonString)
      val outputString = outputObject.data
      assert(inputString === outputString)
    }
  }

}

object CodecSuite {
  final case class JsonRoundTripContainer(data: String)
}