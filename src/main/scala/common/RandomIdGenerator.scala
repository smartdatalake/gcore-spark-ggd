package common

import org.apache.commons.text.RandomStringGenerator

object RandomIdGenerator {

  private val randomStringLength: Int = 5

  private val randomStringIdGenerator: RandomStringGenerator =
    new RandomStringGenerator.Builder().selectFrom(('0' to '9'): _*).build()

  def randomId(length: Int = randomStringLength): String = {
    s"${randomStringIdGenerator.generate(length)}"
  }

}
