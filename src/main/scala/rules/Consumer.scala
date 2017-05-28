/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.rules

import scala.collection.mutable
import viper.silver.ast
import viper.silver.verifier.reasons._
import viper.silver.verifier.{PartialVerificationError, VerificationError}
import viper.silicon.{ConsumeRecord, GlobalBranchRecord, Stack, SymbExLogger}
import viper.silicon.interfaces.{Failure, VerificationResult}
import viper.silicon.state._
import viper.silicon.state.terms._
import viper.silicon.state.terms.predef.`?r`
import viper.silicon.verifier.Verifier

trait ConsumptionRules extends SymbolicExecutionRules {

  /** Consume assertion `a` from state `s`.
    *
    * @param s The state to consume the assertion from.
    * @param a The assertion to consume.
    * @param pve The error to report in case the consumption fails.
    * @param v The verifier to use.
    * @param Q The continuation to invoke if the consumption succeeded, with the following
    *          arguments: state (1st argument) and verifier (3rd argument) resulting from the
    *          consumption, and a heap snapshot (2bd argument )representing the values of the
    *          consumed partial heap.
    * @return The result of the continuation.
    */
  def consume(s: State, a: ast.Exp, pve: PartialVerificationError, v: Verifier)
             (Q: (State, Term, Verifier) => VerificationResult)
             : VerificationResult

  /** Subsequently consumes the assertions `as` (from head to tail), starting in state `s`.
    *
    * `consumes(s, as, _ => pve, v)` should (not yet tested ...) be equivalent to
    * `consume(s, BigAnd(as), pve, v)`, expect that the former allows a more-fine-grained
    * error messages.
    *
    * @param s The state to consume the assertions from.
    * @param as The assertions to consume.
    * @param pvef The error to report in case a consumption fails. Given assertions `as`, an error
    *             `pvef(as_i)` will be reported if consuming assertion `as_i` fails.
    * @param v @see [[consume]]
    * @param Q @see [[consume]]
    * @return @see [[consume]]
    */
  def consumes(s: State,
               as: Seq[ast.Exp],
               pvef: ast.Exp => PartialVerificationError,
               v: Verifier)
              (Q: (State, Term, Verifier) => VerificationResult)
              : VerificationResult
}

object consumer extends ConsumptionRules with Immutable {
  import brancher._
  import evaluator._

  /* See the comment in Producer.scala for an overview of the different produce methods: the
   * different consume methods provided by the consumer work and interact analogously.
   */

  /** @inheritdoc */
  def consume(s: State, a: ast.Exp, pve: PartialVerificationError, v: Verifier)
             (Q: (State, Term, Verifier) => VerificationResult)
             : VerificationResult = {

    consumeR(s, s.h, a.whenExhaling, pve, v)((s1, h1, snap, v1) => {
      val s2 = s1.copy(h = h1,
                       partiallyConsumedHeap = s.partiallyConsumedHeap)
      Q(s2, snap, v1)})
  }

  /** @inheritdoc */
  def consumes(s: State,
               as: Seq[ast.Exp],
               pvef: ast.Exp => PartialVerificationError,
               v: Verifier)
              (Q: (State, Term, Verifier) => VerificationResult)
              : VerificationResult = {

    val allTlcs = mutable.ListBuffer[ast.Exp]()
    val allPves = mutable.ListBuffer[PartialVerificationError]()

    as.foreach(a => {
      val tlcs = a.whenExhaling.topLevelConjuncts
      val pves = Seq.fill(tlcs.length)(pvef(a))

      allTlcs ++= tlcs
      allPves ++= pves
    })

    consumeTlcs(s, s.h, allTlcs.result(), allPves.result(), v)((s1, h1, snap1, v1) => {
      val s2 = s1.copy(h = h1,
                       partiallyConsumedHeap = s.partiallyConsumedHeap)
      Q(s2, snap1, v1)
    })
  }

  private def consumeTlcs(s: State,
                          h: Heap,
                          tlcs: Seq[ast.Exp],
                          pves: Seq[PartialVerificationError],
                          v: Verifier)
                         (Q: (State, Heap, Term, Verifier) => VerificationResult)
                         : VerificationResult = {

    if (tlcs.isEmpty)
      Q(s, h, Unit, v)
    else {
      val a = tlcs.head
      val pve = pves.head

      if (tlcs.tail.isEmpty)
        wrappedConsumeTlc(s, h, a, pve, v)(Q)
      else
        wrappedConsumeTlc(s, h, a, pve, v)((s1, h1, snap1, v1) =>
          consumeTlcs(s1, h1, tlcs.tail, pves.tail, v1)((s2, h2, snap2, v2) =>
            Q(s2, h2, Combine(snap1, snap2), v2)))
    }
  }

  private def consumeR(s: State, h: Heap, a: ast.Exp, pve: PartialVerificationError, v: Verifier)
                      (Q: (State, Heap, Term, Verifier) => VerificationResult)
                      : VerificationResult = {

    val tlcs = a.topLevelConjuncts
    val pves = Seq.fill(tlcs.length)(pve)

    consumeTlcs(s, h, tlcs, pves, v)(Q)
  }

  /** Wrapper/decorator for consume that injects the following operations:
    *   - Logging, see Executor.scala for an explanation
    *   - Failure-driven state consolidation
    */
  protected def wrappedConsumeTlc(s: State,
                                  h: Heap,
                                  a: ast.Exp,
                                  pve: PartialVerificationError,
                                  v: Verifier)
                                 (Q: (State, Heap, Term, Verifier) => VerificationResult)
                                 : VerificationResult = {

    /* tryOrFail effects the "main" heap s.h, so we temporarily set the consume-heap h to be the
     * main heap. Note that the main heap is used for evaluating expressions during an ongoing
     * consume.
     */
    val sInit = s.copy(h = h)
    executionFlowController.tryOrFail2[Heap, Term](sInit, v)((s0, v1, QS) => {
      val h0 = s0.h /* h0 is h, but potentially consolidated */
      val s1 = s0.copy(h = s.h) /* s1 is s, but the retrying flag might be set */

      /* TODO: To remove this cast: Add a type argument to the ConsumeRecord.
       *       Globally the types match, but locally the type system does not know.
       */
      val SEP_identifier = SymbExLogger.currentLog().insert(new ConsumeRecord(a, s1, v.decider.pcs))

      consumeTlc(s1, h0, a, pve, v1)((s2, h2, snap2, v2) => {
        SymbExLogger.currentLog().collapse(a, SEP_identifier)
        QS(s2, h2, snap2, v2)})
    })(Q)
  }

  private def consumeTlc(s: State, h: Heap, a: ast.Exp, pve: PartialVerificationError, v: Verifier)
                        (Q: (State, Heap, Term, Verifier) => VerificationResult)
                        : VerificationResult = {

    /* ATTENTION: Expressions such as `perm(...)` must be evaluated in-place,
     * i.e. in the partially consumed heap which is denoted by `h` here. The
     * evaluator evaluates such expressions in the heap
     * `context.partiallyConsumedHeap`. Hence, this field must be updated every
     * time permissions have been consumed.
     */

    v.logger.debug(s"\nCONSUME ${viper.silicon.utils.ast.sourceLineColumn(a)}: $a")
    v.logger.debug(v.stateFormatter.format(s, v.decider.pcs))
    v.logger.debug("h = " + v.stateFormatter.format(h))
    if (s.reserveHeaps.nonEmpty)
      v.logger.debug("hR = " + s.reserveHeaps.map(v.stateFormatter.format).mkString("", ",\n     ", ""))

    val consumed = a match {
      case imp @ ast.Implies(e0, a0) if !a.isPure =>
        val impLog = new GlobalBranchRecord(imp, s, v.decider.pcs, "consume")
        val sepIdentifier = SymbExLogger.currentLog().insert(impLog)
        SymbExLogger.currentLog().initializeBranching()

        evaluator.eval(s, e0, pve, v)((s1, t0, v1) => {
          impLog.finish_cond()
          val branch_res = branch(s1, t0, v1,
            (s2, v2) => consumeR(s2, h, a0, pve, v2)((s3, h3, snap3, v3) => {
              val res1 = Q(s3, h3, snap3, v3)
              impLog.finish_thnSubs()
              SymbExLogger.currentLog().prepareOtherBranch(impLog)
              res1}),
            (s2, v2) => {
              val res2 = Q(s2, h, Unit, v2)
              impLog.finish_elsSubs()
              res2})
          SymbExLogger.currentLog().collapse(null, sepIdentifier)
          branch_res})

      case ite @ ast.CondExp(e0, a1, a2) if !a.isPure =>
        val gbLog = new GlobalBranchRecord(ite, s, v.decider.pcs, "consume")
        val sepIdentifier = SymbExLogger.currentLog().insert(gbLog)
        SymbExLogger.currentLog().initializeBranching()
        eval(s, e0, pve, v)((s1, t0, v1) => {
          gbLog.finish_cond()
          val branch_res = branch(s1, t0, v1,
            (s2, v2) => consumeR(s2, h, a1, pve, v2)((s3, h3, snap3, v3) => {
              val res1 = Q(s3, h3, snap3, v3)
              gbLog.finish_thnSubs()
              SymbExLogger.currentLog().prepareOtherBranch(gbLog)
              res1}),
            (s2, v2) => consumeR(s2, h, a2, pve, v2)((s3, h3, snap3, v3) => {
              val res2 = Q(s3, h3, snap3, v3)
              gbLog.finish_elsSubs()
              res2}))
          SymbExLogger.currentLog().collapse(null, sepIdentifier)
          branch_res})

      case ast.utility.QuantifiedPermissions.QPForall(qvar, cond, rcvr, field, perm, forall, fa) =>
        val qid = s"prog.l${viper.silicon.utils.ast.sourceLine(forall)}"
        evalQuantified(s, Forall, Seq(qvar.localVar), Seq(cond), Seq(rcvr, perm), None, qid, pve, v){
          case (s1, Seq(tQVar), Seq(tCond), Seq(tRcvr, tPerm), _, Left(tAuxQuantNoTriggers), v1) =>
            v1.decider.assert(Forall(tQVar, Implies(tCond, perms.IsNonNegative(tPerm)), Nil)) {
              case true =>
                val hints = quantifiedChunkSupporter.extractHints(Some(tQVar), Some(tCond), tRcvr)
                val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
                val invFct =
                  quantifiedChunkSupporter.getFreshInverseFunction(tQVar, tRcvr, tCond, tPerm, s1.quantifiedVariables, v1)
                v1.decider.prover.comment("Nested auxiliary terms")
                v1.decider.assume(tAuxQuantNoTriggers.copy(vars = invFct.invOfFct.vars, triggers = invFct.invOfFct.triggers))
                /* TODO: Can we omit/simplify the injectivity check in certain situations? */
                val receiverInjective = quantifiedChunkSupporter.injectivityAxiom(Seq(tQVar), tCond, tPerm, Seq(tRcvr), v1)
                v1.decider.prover.comment("Check receiver injectivity")
                v1.decider.assert(receiverInjective) {
                  case true =>
                    v1.decider.prover.comment("Definitional axioms for inverse functions")
                    v1.decider.assume(invFct.definitionalAxioms)
                    val inverseReceiver = invFct(`?r`) // e⁻¹(r)
                    val loss = PermTimes(tPerm, s1.permissionScalingFactor)
                    quantifiedChunkSupporter.splitLocations(s1, h, field, Some(tQVar), inverseReceiver, tCond, tRcvr, loss, chunkOrderHeuristics, v1) {
                      case Some((s2, h2, ch, fvfDef)) =>
                        val fvfDomain = if (s2.fvfAsSnap) fvfDef.domainDefinitions(invFct) else Seq.empty
                        v1.decider.prover.comment("Definitional axioms for field value function")
                        v1.decider.assume(fvfDomain ++ fvfDef.valueDefinitions)
                        val fr3 = s2.functionRecorder.recordFvfAndDomain(fvfDef, fvfDomain, s2.quantifiedVariables)
                                                     .recordFieldInv(invFct)
                        val s3 = s2.copy(functionRecorder = fr3,
                                         partiallyConsumedHeap = Some(h2))
                        Q(s3, h2, ch.fvf.convert(sorts.Snap), v1)
                      case None =>
                        failure(pve dueTo InsufficientPermission(fa), v1, true)}
                  case false =>
                    failure(pve dueTo ReceiverNotInjective(fa), v1)}
              case false =>
                failure(pve dueTo NegativePermission(perm), v1)}}

      case ast.utility.QuantifiedPermissions.QPPForall(qvar, cond, args, predname, loss, forall, predAccPred) =>
        val predicate = Verifier.program.findPredicate(predname)
        val formalVars = s.predicateFormalVarMap(predicate)
        val qid = s"prog.l${viper.silicon.utils.ast.sourceLine(forall)}"
        //evaluate arguments
        evalQuantified(s, Forall, Seq(qvar.localVar), Seq(cond), args ++ Seq(loss) , None, qid, pve, v) {
          case (s1, Seq(tQVar), Seq(tCond), tArgsGain, _, Left(tAuxQuantNoTriggers), v1) =>
            val (tArgs, Seq(tLoss)) = tArgsGain.splitAt(args.size)
            //assert positve permission
            v1.decider.assert(Forall(tQVar, Implies(tCond, perms.IsNonNegative(tLoss)), Nil)) {
              case true =>
                //check injectivity and define inverse function
                val hints = quantifiedPredicateChunkSupporter.extractHints(Some(tQVar), Some(tCond), tArgs)
                val chunkOrderHeuristics = quantifiedPredicateChunkSupporter.hintBasedChunkOrderHeuristic(hints)
                val invFct = quantifiedPredicateChunkSupporter.getFreshInverseFunction(tQVar, predicate, formalVars, tArgs, tCond, tLoss, s1.quantifiedVariables, v1)
                v1.decider.prover.comment("Nested auxiliary terms")
                v1.decider.assume(tAuxQuantNoTriggers.copy(vars = invFct.invOfFct.vars, triggers = invFct.invOfFct.triggers))
                val isInjective = quantifiedPredicateChunkSupporter.injectivityAxiom(Seq(tQVar), tCond, tLoss, tArgs, v1)
                v1.decider.prover.comment("Check receiver injectivity")
                v1.decider.assert(isInjective) {
                  case true =>
                    v1.decider.prover.comment("Definitional axioms for inverse functions")
                    v1.decider.assume(invFct.definitionalAxioms)
                    val inversePredicate = invFct(formalVars) // e⁻¹(arg1, ..., argn)
                    //remove permission required
                    quantifiedPredicateChunkSupporter.splitLocations(s1, h, predicate, Some(tQVar), inversePredicate, formalVars,  tArgs, tCond, PermTimes(tLoss, s1.permissionScalingFactor), chunkOrderHeuristics, v1) {
                      case Some((s2, h2, ch, psfDef)) =>
                        val psfDomain = if (s2.psfAsSnap) psfDef.domainDefinitions(invFct) else Seq.empty
                        v1.decider.prover.comment("Definitional axioms for predicate snap function")
                        v1.decider.assume(psfDomain ++ psfDef.snapDefinitions)
                        val fr3 = s2.functionRecorder.recordPsfAndDomain(psfDef, psfDomain, s2.quantifiedVariables)
                                                     .recordPredInv(invFct)
                        val s3 = s2.copy(functionRecorder = fr3, partiallyConsumedHeap = Some(h2))
                        Q(s3, h2, ch.psf.convert(sorts.Snap), v1)
                      case None =>
                        failure(pve dueTo InsufficientPermission(predAccPred.loc), v1, true)}
                  case false =>
                    failure(pve dueTo ReceiverNotInjective(predAccPred.loc), v1)}
              case false =>
                failure(pve dueTo NegativePermission(loss), v1)}}

      case ast.AccessPredicate(fa @ ast.FieldAccess(eRcvr, field), perm)
              if s.qpFields.contains(field) =>

        eval(s, eRcvr, pve, v)((s1, tRcvr, v1) =>
          eval(s1, perm, pve, v1)((s2, tPerm, v2) => {
            val hints = quantifiedChunkSupporter.extractHints(None, None, tRcvr)
            val chunkOrderHeuristics = quantifiedChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            val loss = PermTimes(tPerm, s2.permissionScalingFactor)
            quantifiedChunkSupporter.splitSingleLocation(s2, h, field, tRcvr, loss, chunkOrderHeuristics, v2) {
              case Some((s3, h3, ch, fvfDef)) =>
                val fvfDomain = if (s3.fvfAsSnap) fvfDef.domainDefinitions else Seq.empty
                v2.decider.assume(fvfDomain ++ fvfDef.valueDefinitions)
                val fr4 = s3.functionRecorder.recordFvfAndDomain(fvfDef, fvfDomain, s3.quantifiedVariables)
                val s4 = s3.copy(partiallyConsumedHeap = Some(h3),
                                 functionRecorder = fr4)
                Q(s4, h3, ch.valueAt(tRcvr), v2)
              case None => failure(pve dueTo InsufficientPermission(fa), v2, true)}}))

      case ast.AccessPredicate(pa @ ast.PredicateAccess(eArgs, predname), perm)
              if s.qpPredicates.contains(Verifier.program.findPredicate(predname)) =>

        val predicate = Verifier.program.findPredicate(predname)
        val formalVars:Seq[Var] = s.predicateFormalVarMap(predicate)

        evals(s, eArgs, _ => pve, v)((s1, tArgs, v1) =>
          eval(s1, perm, pve, v1)((s2, tPerm, v2) => {
            val hints = quantifiedPredicateChunkSupporter.extractHints(None, None, tArgs)
            val chunkOrderHeuristics = quantifiedPredicateChunkSupporter.hintBasedChunkOrderHeuristic(hints)
            //remove requires permission
            quantifiedPredicateChunkSupporter.splitSingleLocation(s2, h, predicate, tArgs, formalVars, PermTimes(tPerm, s2.permissionScalingFactor), chunkOrderHeuristics, v2) {
              case Some((s3, h3, ch, psfDef)) =>
                val psfDomain = if (s3.psfAsSnap) psfDef.domainDefinitions else Seq.empty
                v2.decider.assume(psfDomain ++ psfDef.snapDefinitions)
                val fr4 = s3.functionRecorder.recordPsfAndDomain(psfDef, psfDomain, s3.quantifiedVariables)
                val s4 = s3.copy(partiallyConsumedHeap = Some(h3),
                                 functionRecorder = fr4)
                Q(s4, h3, ch.valueAt(tArgs), v2)
              case None => failure(pve dueTo InsufficientPermission(pa), v2, true)}}))

      case let: ast.Let if !let.isPure =>
        letSupporter.handle[ast.Exp](s, let, pve, v)((s1, g1, body, v1) => {
          val s2 = s1.copy(g = s1.g + g1)
          val s3 =
            if (s2.recordEffects)
              s2.copy(letBoundVars = s2.letBoundVars ++ g1.values)
            else
              s2
          consumeR(s3, h, body, pve, v1)(Q)})

      case ast.AccessPredicate(locacc, perm) =>
        eval(s, perm, pve, v)((s1, tPerm, v1) =>
          evalLocationAccess(s1, locacc, pve, v1)((s2, name, args, v2) =>
            v2.decider.assert(perms.IsNonNegative(tPerm)){
              case true =>
                val loss = PermTimes(tPerm, s2.permissionScalingFactor)
                chunkSupporter.consume(s2, h, name, args, loss, pve, v2, locacc, Some(a))((s3, h1, snap1, v3) => {
                  val s4 = s3.copy(partiallyConsumedHeap = Some(h1))
                  Q(s4, h1, snap1, v3)})
              case false =>
                failure(pve dueTo NegativePermission(perm), v2)}))

      case _: ast.InhaleExhaleExp =>
        failure(viper.silicon.utils.consistency.createUnexpectedInhaleExhaleExpressionError(a), null)

      /* Handle wands or wand-typed variables */
      case _ if a.typ == ast.Wand && magicWandSupporter.isDirectWand(a) =>
        def QL(s: State, h: Heap, chWand: MagicWandChunk, wand: ast.MagicWand, ve: VerificationError, v: Verifier) = {
          heuristicsSupporter.tryOperation[Heap, Term](s"consume wand $wand")(s, h, v)((s1, h1, v1, QS) => {
            val hs =
              if (s1.exhaleExt) s1.reserveHeaps
              else Stack(h1)

            /* TODO: Consuming a wand chunk, respectively, transferring it if c.exhaleExt
             *       is true, should be implemented on top of MagicWandSupporter.transfer,
             *       or even on ChunkSupporter.consume.
             * TODO: Does context.partiallyConsumedHeap need to be updated after consuming chunks?
             */
            magicWandSupporter.doWithMultipleHeaps(s1, hs, v1)((s2, h2, v2) =>
              magicWandSupporter.getMatchingChunk(h2, chWand, v2) match {
                case someChunk @ Some(ch) => (s2, someChunk, h2 - ch, v2)
                case _ => (s2, None, h2, v2)
              }
            ){case (s2, Some(ch), hs2, v2) =>
                assert(s2.exhaleExt == s.exhaleExt)
                if (s.exhaleExt) {
                  /* transfer: move ch into h = σUsed*/
                  assert(hs2.size == s.reserveHeaps.size)
                  val topReserveHeap = hs2.head + ch
                  val s3 = s2.copy(reserveHeaps = topReserveHeap +: hs2.tail)
                  QS(s3, h /*+ ch*/, v2.decider.fresh(sorts.Snap), v2)
                } else {
                  assert(hs2.size == 1)
                  assert(s2.reserveHeaps == s.reserveHeaps)
                  QS(s2, hs2.head, v2.decider.fresh(sorts.Snap), v2)
                }

              case _ => failure(ve, v1, true)}
          })(Q)
        }

        a match {
          case wand: ast.MagicWand =>
            magicWandSupporter.createChunk(s, wand, pve, v)((s1, chWand, v1) => {
              val ve = pve dueTo MagicWandChunkNotFound(wand)
              QL(s1, h, chWand, wand, ve, v1)})
          case x: ast.AbstractLocalVar =>
            val tWandChunk = s.g(x).asInstanceOf[MagicWandChunkTerm].chunk
            val ve = pve dueTo NamedMagicWandChunkNotFound(x)
            QL(s, h, tWandChunk, tWandChunk.ghostFreeWand, ve, v)
          case _ => sys.error(s"Expected a magic wand, but found node $a")
        }

      case ast.PackagingGhostOp(eWand, eIn) =>
        assert(s.exhaleExt)
        assert(s.reserveHeaps.head.values.isEmpty)
        /** TODO: [[viper.silicon.rules.heuristicsSupporter.packageWand]]
          *       Is essentially a copy of the code here. Re-use code to avoid running out of sync!
          */
        magicWandSupporter.packageWand(s, eWand, pve, v)((s1, chWand, v1) => {
          val hOps = s1.reserveHeaps.head + chWand
          val s2 = s1.copy(exhaleExt = true,
                           reserveHeaps = Heap() +: hOps +: s1.reserveHeaps.tail,
                           lhsHeap = None)
          assert(s2.reserveHeaps.length == s.reserveHeaps.length)
          assert(s2.consumedChunks.length == s.consumedChunks.length)
          assert(s2.consumedChunks.length == s2.reserveHeaps.length - 1)
          val sEmp = s2.copy(h = Heap())
          consumeR(sEmp, sEmp.h, eIn, pve, v1)((s3, h3, _, v3) =>
            Q(s3, h3, v3.decider.fresh(sorts.Snap), v3))})

      case ast.ApplyingGhostOp(eWandOrVar, eIn) =>
        val (eWand, eLHSAndWand, g1) = eWandOrVar match {
          case _eWand: ast.MagicWand =>
            (_eWand, ast.And(_eWand.left, _eWand)(_eWand.left.pos, _eWand.left.info), s.g)
          case x: ast.AbstractLocalVar =>
            val chWand = s.g(x).asInstanceOf[MagicWandChunkTerm].chunk
            val _eWand = chWand.ghostFreeWand
            (_eWand, ast.And(_eWand.left, _eWand)(x.pos, x.info), Store(chWand.bindings))
              /* Note that wand reference x is most likely not bound in tChunk.bindings.
               * Since wands cannot be recursive, this shouldn't be a problem,
               * as long as x doesn't need to be looked up during
               * magicWandSupporter.applyingWand (for whatever reason).
               */
          case _ => sys.error(s"Expected a magic wand, but found node $a")
        }

        heuristicsSupporter.tryOperation[Heap](s"applying $eWand")(s, h, v)((s1, h1, v1, QS) => /* TODO: Why is h1 never used? */
          magicWandSupporter.applyingWand(s1, g1, eWand, eLHSAndWand, pve, v1)(QS)){case (s2, h2, v2) =>
            consumeR(s2, h2, eIn, pve, v2)((s3, h3, _, v3) =>
              Q(s3, h3, v3.decider.fresh(sorts.Snap), v3))}

      case ast.FoldingGhostOp(acc: ast.PredicateAccessPredicate, eIn) =>
        heuristicsSupporter.tryOperation[Heap](s"folding $acc")(s, h, v)((s1, h1, v1, QS) => /* TODO: Why is h1 never used? */
          magicWandSupporter.foldingPredicate(s1, acc, pve, v1)(QS)){case (s2, h2, v2) =>
            consumeR(s2, h2, eIn, pve, v2)((s3, h3, _, v3) =>
              Q(s3, h3, v3.decider.fresh(sorts.Snap), v3))}

      case ast.UnfoldingGhostOp(acc: ast.PredicateAccessPredicate, eIn) =>
        heuristicsSupporter.tryOperation[Heap](s"unfolding $acc")(s, h, v)((s1, h1, v1, QS) => /* TODO: Why is h1 never used? */
          magicWandSupporter.unfoldingPredicate(s1, acc, pve, v1)(QS)){case (s2, h2, v2) =>
            consumeR(s2, h2, eIn, pve, v2)((s3, h3, _, v3) =>
              Q(s3, h3, v3.decider.fresh(sorts.Snap), v3))}

      case _ =>
        evalAndAssert(s, a, pve, v)((s1, t, v1) => {
          Q(s1, h, t, v1)
        })
    }

    consumed
  }

  private def evalAndAssert(s: State, e: ast.Exp, pve: PartialVerificationError, v: Verifier)
                           (Q: (State, Term, Verifier) => VerificationResult)
                           : VerificationResult = {

    /* It is expected that the partially consumed heap (h in the above implementation of
     * `consume`) has already been assigned to `c.partiallyConsumedHeap`.
     *
     * Switch to the eval heap (σUsed) of magic wand's exhale-ext, if necessary.
     * This is done here already (the evaluator would do it as well) to ensure that the eval
     * heap is consolidated by tryOrFail if the assertion fails.
     * The latter is also the reason for wrapping the assertion check in a tryOrFail block:
     * the tryOrFail that wraps the consumption of each top-level conjunct would not consolidate
     * the right heap.
     */
    val s1 = s.copy(h = magicWandSupporter.getEvalHeap(s),
                    reserveHeaps = Nil,
                    exhaleExt = false)

    executionFlowController.tryOrFail0(s1, v)((s2, v1, QS) => {
      eval(s2, e, pve, v1)((s3, t, v2) =>
        v2.decider.assert(t) {
          case true =>
            v2.decider.assume(t)
            QS(s3, v2)
          case false =>
            failure(pve dueTo AssertionFalse(e), v2)})
    })((s4, v4) => {
      val s5 = s4.copy(h = s.h,
                       reserveHeaps = s.reserveHeaps,
                       exhaleExt = s.exhaleExt)
      Q(s5, Unit, v4)
    })
  }
}
