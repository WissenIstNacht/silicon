/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper
package silicon
package decider

import java.io.{PrintWriter, BufferedWriter, InputStreamReader, BufferedReader, OutputStreamWriter}
import java.nio.file.{Path, Paths}
import org.slf4s.Logging
import org.apache.commons.io.FileUtils
import common.config.Version
import interfaces.decider.{Prover, Sat, Unsat, Unknown}
import reporting.{Bookkeeper, Z3InteractionFailed}
import state.terms._
import silicon.utils.Counter

/* TODO: Pass a logger, don't open an own file to log to. */
class Z3ProverStdIO(config: Config, bookkeeper: Bookkeeper) extends Prover with Logging {
  val termConverter = new TermToSMTLib2Converter(bookkeeper)
  import termConverter._

  private var pushPopScopeDepth = 0
  private var isLoggingCommentsEnabled: Boolean = true
  private var logFile: PrintWriter = _
  private var z3: Process = _
  private var input: BufferedReader = _
  private var output: PrintWriter = _
  private var _path: Path = _
  private var logPath: Path = _
  private var counter: Counter = _
  private var lastTimeout: Int = 0

  def path() = _path

  def version(): Version = {
    val versionPattern = """\(?\s*:version\s+"(.*?)"\)?""".r
    var line = ""

    writeLine("(get-info :version)")

    line = input.readLine()
    logComment(line)

    line match {
      case versionPattern(v) => Version(v)
      case _ => throw new Z3InteractionFailed(s"Unexpected output of Z3 while getting version: $line")
    }
  }

  def start() {
    counter = new Counter()
    logPath = config.z3LogFile
    logFile = silver.utility.Common.PrintWriter(logPath.toFile)
    _path = Paths.get(config.z3Exe)
    z3 = createZ3Instance()
    input = new BufferedReader(new InputStreamReader(z3.getInputStream))
    output = new PrintWriter(new BufferedWriter(new OutputStreamWriter(z3.getOutputStream)), true)
  }

  /* Note: This is just a hack to get the input file name to the prover */
  def proverRunStarts() {
    logComment(s"Input file is ${config.inputFile.getOrElse("<unknown>")}")
  }

  private def createZ3Instance() = {
    log.info(s"Starting Z3 at ${_path}")

    val userProvidedZ3Args: Array[String] = config.z3Args.get match {
      case None =>
        Array()

      case Some(args) =>
        log.info(s"Additional command-line arguments are $args")
        args.split(' ').map(_.trim)
    }

    val builder = new ProcessBuilder(_path.toFile.getPath +: "-smt2" +: "-in" +: userProvidedZ3Args :_*)
    builder.redirectErrorStream(true)

    val process = builder.start()

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run() {
        process.destroy()
      }
    })

    process
  }

  def reset() {
    stop()
    counter.reset()
    pushPopScopeDepth = 0
    lastTimeout = 0
    start()
  }

  def stop() {
    this.synchronized {
      logFile.flush()
      output.flush()

      logFile.close()
      input.close()
      output.close()

      z3.destroy()
//      z3.waitFor() /* Makes the current thread wait until the process has been shut down */

      val currentLogPath = config.z3LogFile
      if (logPath != currentLogPath) {
        /* This is a hack to make it possible to name the SMTLIB logfile after
         * the input file that was verified. Currently, Silicon starts Z3 before
         * the input file name is known, which is partially due to our crappy
         * and complicated way of how command-line arguments are parsed and
         * how Silver programs are passed to verifiers.
         */

        FileUtils.moveFile(logPath.toFile, currentLogPath.toFile)
      }
    }
  }

  def push(n: Int = 1) {
    pushPopScopeDepth += n
    val cmd = (if (n == 1) "(push)" else "(push " + n + ")") + " ; " + pushPopScopeDepth
    writeLine(cmd)
    readSuccess()
  }

  def pop(n: Int = 1) {
    val cmd = (if (n == 1) "(pop)" else "(pop " + n + ")") + " ; " + pushPopScopeDepth
    pushPopScopeDepth -= n
    writeLine(cmd)
    readSuccess()
  }

  def write(content: String) {
    writeLine(content)
    readSuccess()
  }

  private val quantificationLogger = bookkeeper.logfiles("quantification-problems")

  def assume(term: Term) = {
    /* Detect certain simple problems with quantifiers.
     * Note that the current checks don't take in account whether or not a
     * quantification occurs in positive or negative position.
     */
    term.deepCollect{case q: Quantification => q}.foreach(q => {
      val problems = state.utils.detectQuantificationProblems(q)

      if (problems.nonEmpty) {
        quantificationLogger.println(s"\n\n${q.toString(true)}")
        quantificationLogger.println("Problems:")
        problems.foreach(p => quantificationLogger.println(s"  $p"))
      }
    })

    assume(convert(term))
  }

  def assume(term: String) {
    bookkeeper.assumptionCounter += 1

    writeLine("(assert " + term + ")")
    readSuccess()
  }

  def assert(goal: Term, timeout: Int = 0) = assert(convert(goal), timeout)

  def assert(goal: String, timeout: Int) = {
    bookkeeper.assertionCounter += 1

    setTimeout(timeout)

    val (result, duration) = config.assertionMode() match {
      case Config.AssertionMode.SoftConstraints => assertUsingSoftConstraints(goal)
      case Config.AssertionMode.PushPop => assertUsingPushPop(goal)
    }

    logComment(s"${common.format.formatMillisReadably(duration)}")
    logComment("(get-info :all-statistics)")

    result
  }

  private def assertUsingPushPop(goal: String): (Boolean, Long) = {
    push()

    writeLine("(assert (not " + goal + "))")
    readSuccess()

    val startTime = System.currentTimeMillis()
    writeLine("(check-sat)")
    val result = readUnsat()
    val endTime = System.currentTimeMillis()

    pop()

    (result, endTime - startTime)
  }

  private def assertUsingSoftConstraints(goal: String): (Boolean, Long) = {
    val guard = fresh("grd", sorts.Bool)

    writeLine(s"(assert (implies $guard (not $goal)))")
    readSuccess()

    val startTime = System.currentTimeMillis()
    writeLine(s"(check-sat $guard)")
    val result = readUnsat()
    val endTime = System.currentTimeMillis()

    (result, endTime - startTime)
  }

  def check(timeout: Int = 0) = {
    setTimeout(timeout)

    writeLine("(check-sat)")

    readLine() match {
      case "sat" => Sat
      case "unsat" => Unsat
      case "unknown" => Unknown
    }
  }

  private def setTimeout(timeout: Int) {
    /* [2015-07-27 Malte] Setting the timeout unnecessarily often seems to
     * worsen performance, if only a bit. For the current test suite of
     * 199 Silver files, the total verification time increased from 60s
     * to 70s if 'set-option' is emitted every time.
     */
    if (lastTimeout != timeout) {
      lastTimeout = timeout

      writeLine(s"(set-option :timeout $timeout)")
      readSuccess()
    }
  }

  def statistics(): Map[String, String]= {
    var repeat = true
    var line = ""
    var stats = scala.collection.immutable.SortedMap[String, String]()
    val entryPattern = """\(?\s*:([A-za-z\-]+)\s+((?:\d+\.)?\d+)\)?""".r

    writeLine("(get-info :all-statistics)")

    do {
      line = input.readLine()
      logComment(line)

      /* Check that the first line starts with "(:". */
      if (line.isEmpty && !line.startsWith("(:"))
        throw new Z3InteractionFailed(s"Unexpected output of Z3 while reading statistics: $line")

      line match {
        case entryPattern(entryName, entryNumber) =>
          stats = stats + (entryName -> entryNumber)
        case _ =>
      }

      repeat = !line.endsWith(")")
    } while (repeat)

    toMap(stats)
  }

  def enableLoggingComments(enabled: Boolean) = isLoggingCommentsEnabled = enabled

  def logComment(str: String) =
    if (isLoggingCommentsEnabled) {
      val sanitisedStr =
        str.replaceAll("\r", "")
           .replaceAll("\n", "\n; ")

      logToFile("; " + sanitisedStr)
    }

  private def freshId(prefix: String) = prefix + "@" + counter.next()

  /* TODO: Could we decouple fresh from Var, e.g. return the used freshId, without
   *       losing conciseness at call-site?
   *       It is also slightly fishy that fresh returns a Var although it
   *       declared a new Function.
   */
  def fresh(idPrefix: String, sort: Sort) = {
    val id = freshId(idPrefix)

    val decl = sort match {
      case arrow: sorts.Arrow => FunctionDecl(Function(id, arrow))
      case _ => VarDecl(Var(id, sort))
    }

    write(convert(decl))

    Var(id, sort)
  }

  def sanitizeSymbol(symbol: String) = termConverter.sanitizeSymbol(symbol)

  def declare(decl: Decl) {
    val str = convert(decl)
    write(str)
  }

  def resetAssertionCounter() { bookkeeper.assertionCounter = 0 }
  def resetAssumptionCounter() { bookkeeper.assumptionCounter = 0 }

  def resetCounters() {
    resetAssertionCounter()
    resetAssumptionCounter()
  }

  /* TODO: Handle multi-line output, e.g. multiple error messages. */

  private def readSuccess() {
    val answer = readLine()

    if (answer != "success")
      throw new Z3InteractionFailed(s"Unexpected output of Z3. Expected 'success' but found: $answer")
  }

  private def readUnsat(): Boolean = readLine() match {
    case "unsat" => true
    case "sat" => false
    case "unknown" => false

    case result =>
      throw new Z3InteractionFailed(s"Unexpected output of Z3 while trying to refute an assertion: $result")
  }

  private def readLine(): String = {
    var repeat = true
    var result = ""

    while (repeat) {
      result = input.readLine()
      if (result.toLowerCase != "success") logComment(result)

      val warning = result.startsWith("WARNING")
      if (warning) log.info(s"Z3: $result")

      repeat = warning
    }

    result
  }

  private def logToFile(str: String) {
    logFile.println(str)
  }

  private def writeLine(out: String) = {
    logToFile(out)
    output.println(out)
  }
}
