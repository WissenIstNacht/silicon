/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper
package silicon
package reporting

import silver.ast
import silver.verifier.AbstractError

/* TODO: Distinguish between the exception that can be thrown and the error that is reported back to e.g. Scala2Sil. */
case class ProverInteractionFailed(message: String) extends RuntimeException(message) with AbstractError {
  def pos = ast.NoPosition
  def fullId = "prover.interaction.failed"
  def readableMessage = s"Interaction with the prover failed: $message"
}

case class VerificationException(error: AbstractError) extends RuntimeException(error.readableMessage)
