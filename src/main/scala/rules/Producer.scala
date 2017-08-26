/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.rules

import viper.silicon.{GlobalBranchRecord, ProduceRecord, SymbExLogger}

import scala.collection.mutable
import viper.silver.ast
import viper.silver.ast.utility.QuantifiedPermissions.QuantifiedPermissionAssertion
import viper.silver.verifier.PartialVerificationError
import viper.silicon.interfaces.{Failure, VerificationResult}
import viper.silicon.state.{FieldChunk, PredicateChunk, State}
import viper.silicon.state.terms.{App, _}
import viper.silicon.state.terms.perms.IsPositive
import viper.silicon.state.terms.predef.`?r`
import viper.silicon.supporters.functions.NoopFunctionRecorder
import viper.silicon.verifier.Verifier

trait ProductionRules extends SymbolicExecutionRules {

  /** Produce assertion `a` into state `s`.
    *
    * @param s The state to produce the assertion into.
    * @param sf The heap snapshot determining the values of the produced partial heap.
    * @param a The assertion to produce.
    * @param pve The error to report in case the production fails.
    * @param v The verifier to use.
    * @param Q The continuation to invoke if the production succeeded, with the state and
    *          the verifier resulting from the production as arguments.
    * @return The result of the continuation.
    */
  def produce(s: State,
              sf: (Sort, Verifier) => Term,
              a: ast.Exp,
              pve: PartialVerificationError,
              v: Verifier)
             (Q: (State, Verifier) => VerificationResult)
             : VerificationResult

  /** Subsequently produces assertions `as` into state `s`.
    *
    * `produces(s, sf, as, _ => pve, v)` should (not yet tested ...) be equivalent to
    * `produce(s, sf, BigAnd(as), pve, v)`, expect that the former allows a more-fine-grained
    * error messages.
    *
    * @param s The state to produce the assertions into.
    * @param sf The heap snapshots determining the values of the produced partial heaps.
    * @param as The assertions to produce.
    * @param pvef The error to report in case the production fails. Given assertions `as`, an error
    *             `pvef(as_i)` will be reported if producing assertion `as_i` fails.
    * @param v @see [[produce]]
    * @param Q @see [[produce]]
    * @return @see [[produce]]
    */
  def produces(s: State,
               sf: (Sort, Verifier) => Term,
               as: Seq[ast.Exp],
               pvef: ast.Exp => PartialVerificationError,
               v: Verifier)
              (Q: (State, Verifier) => VerificationResult)
              : VerificationResult
}

object producer extends ProductionRules with Immutable {
  import brancher._
  import evaluator._

  /* Overview of and interaction between the different available produce-methods:
   *   - `produce` and `produces` are the entry methods and intended to be called by *clients*
   *     (e.g. from the executor), but *not* by the implementation of the producer itself
   *     (e.g. recursively).
   *   - Produce methods suffixed with `tlc` (or `tlcs`) expect top-level conjuncts as assertions.
   *     The other produce methods therefore split the given assertion(s) into top-level
   *     conjuncts and forward these to `produceTlcs`.
   *   - `produceTlc` implements the actual symbolic execution rules for producing an assertion,
   *     and `produceTlcs` is basically `produceTlc` lifted to a sequence of assertions
   *     (a continuation-aware fold, if you will).
   *   - Certain operations such as logging need to be performed per produced top-level conjunct.
   *     This is implemented by `wrappedProduceTlc`: a wrapper around (or decorator for)
   *     `produceTlc` that performs additional operations before/after invoking `produceTlc`.
   *     `produceTlcs` therefore repeatedly invokes `wrappedProduceTlc` (and not `produceTlc`
   *     directly)
   *   - `produceR` is intended for recursive calls: it takes an assertion, splits it into
   *     top-level conjuncts and uses `produceTlcs` to produce each of them (hence, each assertion
   *     to produce passes `wrappedProduceTlc` before finally reaching `produceTlc`).
   *   - Note that the splitting into top-level conjuncts performed by `produceR` is not redundant,
   *     although the entry methods such as `produce` split assertions as well: if a client needs
   *     to produce `a1 && (b ==> a2 && a3) && a4`, then the entry method will split the assertion
   *     into the sequence `[a1, b ==> a2 && a3, a4]`, and the recursively produced assertion
   *     `a2 && a3` (after having branched over `b`) needs to be split again.
   */

  /** @inheritdoc */
  def produce(s: State,
              sf: (Sort, Verifier) => Term,
              a: ast.Exp,
              pve: PartialVerificationError,
              v: Verifier)
             (Q: (State, Verifier) => VerificationResult)
             : VerificationResult =

    produceR(s, sf, a.whenInhaling, pve, v)(Q)

  /** @inheritdoc */
  def produces(s: State,
               sf: (Sort, Verifier) => Term,
               as: Seq[ast.Exp],
               pvef: ast.Exp => PartialVerificationError,
               v: Verifier)
              (Q: (State, Verifier) => VerificationResult)
              : VerificationResult = {

    val allTlcs = mutable.ListBuffer[ast.Exp]()
    val allPves = mutable.ListBuffer[PartialVerificationError]()

    as.foreach(a => {
      val tlcs = a.whenInhaling.topLevelConjuncts
      val pves = Seq.fill(tlcs.length)(pvef(a))

      allTlcs ++= tlcs
      allPves ++= pves
    })

    produceTlcs(s, sf, allTlcs.result(), allPves.result(), v)(Q)
  }

  private def produceTlcs(s: State,
                          sf: (Sort, Verifier) => Term,
                          as: Seq[ast.Exp],
                          pves: Seq[PartialVerificationError],
                          v: Verifier)
                         (Q: (State, Verifier) => VerificationResult)
                         : VerificationResult = {

    if (as.isEmpty)
      Q(s, v)
    else {
      val a = as.head.whenInhaling
      val pve = pves.head

      if (as.tail.isEmpty)
        wrappedProduceTlc(s, sf, a, pve, v)(Q)
      else {
        val (sf0, sf1) =
          v.snapshotSupporter.createSnapshotPair(s, sf, a, viper.silicon.utils.ast.BigAnd(as.tail), v)
          /* TODO: Refactor createSnapshotPair s.t. it can be used with Seq[Exp],
           *       then remove use of BigAnd; for one it is not efficient since
           *       the tail of the (decreasing list parameter Ï†s) is BigAnd-ed
           *       over and over again.
           */

        wrappedProduceTlc(s, sf0, a, pve, v)((s1, v1) =>
          produceTlcs(s1, sf1, as.tail, pves.tail, v1)(Q))
      }
    }
  }

  private def produceR(s: State,
                       sf: (Sort, Verifier) => Term,
                       a: ast.Exp,
                       pve: PartialVerificationError,
                       v: Verifier)
                      (Q: (State, Verifier) => VerificationResult)
                      : VerificationResult = {

    val tlcs = a.topLevelConjuncts
    val pves = Seq.fill(tlcs.length)(pve)

    produceTlcs(s, sf, tlcs, pves, v)(Q)
  }

  /** Wrapper/decorator for consume that injects the following operations:
    *   - Logging, see Executor.scala for an explanation
    */
  private def wrappedProduceTlc(s: State,
                                sf: (Sort, Verifier) => Term,
                                a: ast.Exp,
                                pve: PartialVerificationError,
                                v: Verifier)
                               (Q: (State, Verifier) => VerificationResult)
                               : VerificationResult = {

    val sepIdentifier = SymbExLogger.currentLog().insert(new ProduceRecord(a, s, v.decider.pcs))
    produceTlc(s, sf, a, pve, v)((s1, v1) => {
      SymbExLogger.currentLog().collapse(a, sepIdentifier)
      Q(s1, v1)})
  }

  private def produceTlc(s: State,
                         sf: (Sort, Verifier) => Term,
                         a: ast.Exp,
                         pve: PartialVerificationError,
                         v: Verifier)
                        (Q: (State, Verifier) => VerificationResult)
                        : VerificationResult = {

    v.logger.debug(s"\nPRODUCE ${viper.silicon.utils.ast.sourceLineColumn(a)}: $a")
    v.logger.debug(v.stateFormatter.format(s, v.decider.pcs))

    val produced = a match {
      case imp @ ast.Implies(e0, a0) if !a.isPure =>
        val impLog = new GlobalBranchRecord(imp, s, v.decider.pcs, "produce")
        val sepIdentifier = SymbExLogger.currentLog().insert(impLog)
        SymbExLogger.currentLog().initializeBranching()

        eval(s, e0, pve, v)((s1, t0, v1) => {
          impLog.finish_cond()
          val branch_res =
            branch(s1, t0, v1)(
              (s2, v2) => produceR(s2, sf, a0, pve, v2)((s3, v3) => {
                val res1 = Q(s3, v3)
                impLog.finish_thnSubs()
                SymbExLogger.currentLog().prepareOtherBranch(impLog)
                res1}),
              (s2, v2) => {
                v2.decider.assume(sf(sorts.Snap, v2) === Unit)
                  /* TODO: Avoid creating a fresh var (by invoking) `sf` that is not used
                   * otherwise. In order words, only make this assumption if `sf` has
                   * already been used, e.g. in a snapshot equality such as `s0 == (s1, s2)`.
                   */
                val res2 = Q(s2,  v2)
                impLog.finish_elsSubs()
                res2})
          SymbExLogger.currentLog().collapse(null, sepIdentifier)
          branch_res})

      case ite @ ast.CondExp(e0, a1, a2) if !a.isPure =>
        val gbLog = new GlobalBranchRecord(ite, s, v.decider.pcs, "produce")
        val sepIdentifier = SymbExLogger.currentLog().insert(gbLog)
        SymbExLogger.currentLog().initializeBranching()
        eval(s, e0, pve, v)((s1, t0, v1) => {
          gbLog.finish_cond()
          val branch_res =
            branch(s1, t0, v1)(
              (s2, v2) => produceR(s2, sf, a1, pve, v2)((s3, v3) => {
                val res1 = Q(s3, v3)
                gbLog.finish_thnSubs()
                SymbExLogger.currentLog().prepareOtherBranch(gbLog)
                res1}),
              (s2, v2) => produceR(s2, sf, a2, pve, v2)((s3, v3) => {
                val res2 = Q(s3, v3)
                gbLog.finish_elsSubs()
                res2}))
          SymbExLogger.currentLog().collapse(null, sepIdentifier)
          branch_res})

      case let: ast.Let if !let.isPure =>
        letSupporter.handle[ast.Exp](s, let, pve, v)((s1, g1, body, v1) =>
          produceR(s1.copy(g = s1.g + g1), sf, body, pve, v1)(Q))

      case ast.FieldAccessPredicate(ast.FieldAccess(eRcvr, field), perm) =>
        /* TODO: Verify similar to the code in DefaultExecutor/ast.NewStmt - unify */
        def addNewChunk(s: State, rcvr: Term, snap: Term, p: Term, v: Verifier)
                       (Q: (State, Verifier) => VerificationResult)
                       : VerificationResult = {

          if (s.qpFields.contains(field)) {
            val (sm, smValueDef) =
              quantifiedChunkSupporter.singletonSnapshotMap(s, field, Seq(rcvr), snap, v)
            v.decider.prover.comment("Definitional axioms for singleton-SM's value")
            v.decider.assume(smValueDef)
            val ch = quantifiedChunkSupporter.createSingletonQuantifiedChunk(Seq(`?r`), field, Seq(rcvr), p, sm)
            val smDef = SnapshotMapDefinition(field, sm, Seq(smValueDef), Seq())
            val s1 = s.copy(functionRecorder = s.functionRecorder.recordFvfAndDomain(smDef))
            Q(s1.copy(h = s1.h + ch), v)
          } else {
            val ch = FieldChunk(rcvr, field.name, snap, p)
            chunkSupporter.produce(s, s.h, ch, v)((s1, h1, v1) =>
              Q(s1.copy(h = h1), v1))
          }
        }

        eval(s, eRcvr, pve, v)((s1, tRcvr, v1) =>
          eval(s1, perm, pve, v1)((s2, tPerm, v2) => {
            v2.decider.assume(PermAtMost(NoPerm(), tPerm))
            v2.decider.assume(Implies(IsPositive(tPerm), tRcvr !== Null()))
            val snap = sf(v2.symbolConverter.toSort(field.typ), v2)
            val gain = PermTimes(tPerm, s2.permissionScalingFactor)
            addNewChunk(s2, tRcvr, snap, gain, v2)(Q)}))

      case ast.PredicateAccessPredicate(ast.PredicateAccess(eArgs, predicateName), perm) =>
        val predicate = Verifier.program.findPredicate(predicateName)

        def addNewChunk(s: State, args: Seq[Term], snap: Term, p: Term, v: Verifier)
                       (Q: (State, Verifier) => VerificationResult)
                       : VerificationResult = {

          if (s.qpPredicates.contains(predicate)) {
            val formalArgs = s.predicateFormalVarMap(predicate)
            val (sm, smValueDef) =
              quantifiedChunkSupporter.singletonSnapshotMap(s, predicate, args, snap, v)
            v.decider.prover.comment("Definitional axioms for singleton-SM's value")
            v.decider.assume(smValueDef)
            val ch = quantifiedChunkSupporter.createSingletonQuantifiedChunk(formalArgs, predicate, args, p, sm)
            val smDef = SnapshotMapDefinition(predicate, sm, Seq(smValueDef), Seq())
            val s1 = s.copy(functionRecorder = s.functionRecorder.recordFvfAndDomain(smDef))
            Q(s1.copy(h = s1.h + ch), v)
          } else {
            val snap1 = snap.convert(sorts.Snap)
            val ch = PredicateChunk(predicate.name, args, snap1, p)
            chunkSupporter.produce(s, s.h, ch, v)((s1, h1, v1) => {
              if (Verifier.config.enablePredicateTriggersOnInhale() && s1.functionRecorder == NoopFunctionRecorder)
              v1.decider.assume(App(Verifier.predicateData(predicate).triggerFunction, snap1 +: args))
              Q(s1.copy(h = h1), v1)
            })
          }
        }

        evals(s, eArgs, _ => pve, v)((s1, tArgs, v1) =>
          eval(s1, perm, pve, v1)((s2, tPerm, v2) => {
            v2.decider.assume(PermAtMost(NoPerm(), tPerm))
            val snap = sf(
              predicate.body.map(v2.snapshotSupporter.optimalSnapshotSort(_, Verifier.program)._1)
                            .getOrElse(sorts.Snap), v2)
            val gain = PermTimes(tPerm, s2.permissionScalingFactor)
            addNewChunk(s2, tArgs, snap, gain, v2)(Q)}))

      case wand: ast.MagicWand =>
        magicWandSupporter.createChunk(s, wand, pve, v)((s1, chWand, v1) =>
          Q(s1.copy(h = s1.h + chWand), v1))

      /* TODO: Initial handling of QPs is identical/very similar in consumer
       *       and producer. Try to unify the code.
       */
      case QuantifiedPermissionAssertion(forall, cond, acc: ast.FieldAccessPredicate) =>
        val qid = s"qp.l${viper.silicon.utils.ast.sourceLine(forall)}${v.counter(this).next()}"
        val optTrigger =
          if (forall.triggers.isEmpty) None
          else Some(forall.triggers)
        evalQuantified(s, Forall, forall.variables, Seq(cond), Seq(acc.loc.rcv, acc.perm), optTrigger, qid, pve, v){
          case (s1, qvars, Seq(tCond), Seq(tRcvr, tPerm), tTriggers, auxQuantResult, v1) =>
            val snap = sf(sorts.FieldValueFunction(v1.symbolConverter.toSort(acc.loc.field.typ)), v1)
            val gain = PermTimes(tPerm, s1.permissionScalingFactor)
            val (ch, inverseFunctions) =
              quantifiedChunkSupporter.createQuantifiedChunk(
                qvars                = qvars,
                condition            = tCond,
                location             = acc.loc.field,
                arguments            = Seq(tRcvr),
                permissions          = gain,
                codomainQVars        = Seq(`?r`),
                sm                   = snap,
                additionalInvArgs    = s1.relevantQuantifiedVariables(Seq(tRcvr)),
                userProvidedTriggers = optTrigger.map(_ => tTriggers),
                qidPrefix            = qid,
                v                    = v1)
            val (effectiveTriggers, effectiveTriggersQVars) =
              optTrigger match {
                case Some(_) =>
                  /* Explicit triggers were provided */
                  (tTriggers, qvars)
                case None =>
                  /* No explicit triggers were provided and we resort to those from the inverse
                   * function axiom inv-of-rcvr, i.e. from `inv(e(x)) = x`.
                   * Note that the trigger generation code might have added quantified variables
                   * to that axiom.
                   */
                  (inverseFunctions.axiomInversesOfInvertibles.triggers,
                   inverseFunctions.axiomInversesOfInvertibles.vars)
              }

            if (effectiveTriggers.isEmpty)
              v1.logger.warn(s"No triggers available for quantifier at ${forall.pos}")

            v1.decider.prover.comment("Nested auxiliary terms")
            auxQuantResult match {
              case Left(tAuxQuantNoTriggers) =>
                /* No explicit triggers provided */
                v1.decider.assume(
                  tAuxQuantNoTriggers.copy(
                    vars = effectiveTriggersQVars,
                    triggers = effectiveTriggers))

              case Right(tAuxQuants) =>
                /* Explicit triggers were provided. */
                v1.decider.assume(tAuxQuants)
            }
            v1.decider.prover.comment("Definitional axioms for inverse functions")
            v1.decider.assume(inverseFunctions.definitionalAxioms)

            val gainNonNeg =
              quantifiedChunkSupporter.permissionsNonNegativeAxioms(
                qvars     = effectiveTriggersQVars,
                perms     = gain,
                triggers  = effectiveTriggers,
                qidPrefix = qid)
            v1.decider.prover.comment("Produced permissions are non-negative")
            v1.decider.assume(gainNonNeg)

            val tNonNullQuant =
              quantifiedChunkSupporter.receiverNonNullAxiom(
                qvars     = effectiveTriggersQVars,
                condition  = tCond,
                receiver  = tRcvr,
                perms = gain,
                triggers  = effectiveTriggers,
                qidPrefix = qid)

            v1.decider.prover.comment("Receivers are non-null")
            v1.decider.assume(tNonNullQuant)
            val s2 = s1.copy(functionRecorder = s1.functionRecorder.recordFieldInv(inverseFunctions))
            Q(s2.copy(h = s2.h + ch), v1)}

      case QuantifiedPermissionAssertion(forall, cond, acc: ast.PredicateAccessPredicate) =>
        val predicate = Verifier.program.findPredicate(acc.loc.predicateName)
        val formalVars = s.predicateFormalVarMap(predicate)
        val qid = s"qp.l${viper.silicon.utils.ast.sourceLine(forall)}${v.counter(this).next()}"
        val optTrigger =
          if (forall.triggers.isEmpty) None
          else Some(forall.triggers)
        evalQuantified(s, Forall, forall.variables, Seq(cond), acc.perm +: acc.loc.args, optTrigger, qid, pve, v) {
          case (s1, qvars, Seq(tCond), Seq(tPerm, tArgs @ _*), tTriggers, auxQuantResult, v1) =>
            val snap = sf(sorts.PredicateSnapFunction(s1.predicateSnapMap(predicate)), v1)
            val gain = PermTimes(tPerm, s1.permissionScalingFactor)
            val (ch, inverseFunctions) =
              quantifiedChunkSupporter.createQuantifiedChunk(
                qvars                = qvars,
                condition            = tCond,
                location             = predicate,
                arguments            = tArgs,
                permissions          = gain,
                sm                   = snap,
                codomainQVars        = formalVars,
                additionalInvArgs    = s1.relevantQuantifiedVariables(tArgs),
                userProvidedTriggers = optTrigger.map(_ => tTriggers),
                qidPrefix            = qid,
                v                    = v1)
            val (effectiveTriggers, effectiveTriggersQVars) =
              optTrigger match {
                case Some(_) =>
                  /* Explicit triggers were provided */
                  (tTriggers, qvars)
                case None =>
                  /* No explicit triggers were provided and we resort to those from the inverse
                   * function axiom inv-of-rcvr, i.e. from `inv(e(x)) = x`.
                   * Note that the trigger generation code might have added quantified variables
                   * to that axiom.
                   */
                  (inverseFunctions.axiomInversesOfInvertibles.triggers,
                   inverseFunctions.axiomInversesOfInvertibles.vars)
              }
            if (effectiveTriggers.isEmpty)
              v1.logger.warn(s"No triggers available for quantifier at ${forall.pos}")

            v1.decider.prover.comment("Nested auxiliary terms")
            auxQuantResult match {
              case Left(tAuxQuantNoTriggers) =>
                /* No explicit triggers provided */
                v1.decider.assume(
                  tAuxQuantNoTriggers.copy(
                    vars = effectiveTriggersQVars,
                    triggers = effectiveTriggers))

              case Right(tAuxQuants) =>
                /* Explicit triggers were provided. */
                v1.decider.assume(tAuxQuants)
            }

            v1.decider.prover.comment("Definitional axioms for inverse functions")
            v1.decider.assume(inverseFunctions.definitionalAxioms)

            val gainNonNeg =
              quantifiedChunkSupporter.permissionsNonNegativeAxioms(
                qvars     = effectiveTriggersQVars,
                perms     = gain,
                triggers  = effectiveTriggers,
                qidPrefix = qid)
            v1.decider.prover.comment("Produced permissions are non-negative")
            v1.decider.assume(gainNonNeg)

            val s2 = s1.copy(functionRecorder = s1.functionRecorder.recordFieldInv(inverseFunctions))
            Q(s2.copy(h = s2.h + ch), v1)}

      case _: ast.InhaleExhaleExp =>
        failure(viper.silicon.utils.consistency.createUnexpectedInhaleExhaleExpressionError(a), null)

      /* Any regular expressions, i.e. boolean and arithmetic. */
      case _ =>
        v.decider.assume(sf(sorts.Snap, v) === Unit) /* TODO: See comment for case ast.Implies above */
        eval(s, a, pve, v)((s1, t, v1) => {
          v1.decider.assume(t)
          Q(s1, v1)})
    }

    produced
  }
}
