/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.rules

import viper.silicon.interfaces.state._
import viper.silicon.interfaces.{Failure, VerificationResult}
import viper.silicon.state._
import viper.silicon.state.terms._
import viper.silicon.state.terms.predef.`?r`
import viper.silicon.state.terms.utils.{BigPermSum, consumeExactRead}
import viper.silicon.supporters.qps.{FvfDefinition, QuantifiedChunkFvfDefinition, SingletonChunkFvfDefinition, SummarisingFvfDefinition}
import viper.silicon.verifier.Verifier
import viper.silver.ast
import viper.silver.verifier.PartialVerificationError
import viper.silver.verifier.reasons.InsufficientPermission

case class InverseFunction(func: Function,
                           function: Term => Term,
                           invOfFct: Quantification,
                           fctOfInv: Quantification) {

  val definitionalAxioms = invOfFct :: fctOfInv :: Nil
  def apply(t: Term) = function(t)
}

trait QuantifiedChunkSupport extends SymbolicExecutionRules {
  def getFreshInverseFunction(qvar: Var,
                              fct: Term,
                              condition: Term,
                              perms: Term,
                              additionalInvArgs: Seq[Var],
                              v: Verifier)
                             : InverseFunction

  /** Returns a FVF that represents a *single* heap location.
    *
    * Attention: don't use this method in a context where the receiver is indirectly quantified
    * over! An example of such a situation is the following:
    *
    *   function f(x: Ref): Int
    *     requires acc(x.f)
    *
    *   // client
    *   inhale forall y in ys :: acc(y.f)
    *   inhale forall y in ys :: f(y)
    *
    * Consuming the precondition of f(y) entails consuming acc(y.f), which yields a FVF that
    * represents a single heap location (namely y.f). However, this FVF should actually represent
    * a family of singleton heaps - one per possible instantiation of y - and hence, the FVF
    * should depend on y.
    * However, this method does not create - or rather, does not allow creating - singleton FVFs
    * that depend on additional arguments.
    *
    * @param s Current state.
    * @param field Field for which to create a FVF.
    * @param rcvr Receiver (non-quantified!); `rcvr.field` denotes the single heap location
    *             represented by the returned FVF.
    * @param value Value of the single heap location. I.e. looking up `rcvr` in the returned FVF
    *              will yield `value`.
    * @param v Current verifier.
    * @return The newly-created FVF.
    */
  def createSingletonFieldValueFunction(s: State,
                                        field: ast.Field,
                                        rcvr: Term,
                                        value: Term,
                                        v: Verifier)
                                       : (Term, Option[SingletonChunkFvfDefinition])

  def injectivityAxiom(qvars: Seq[Var], condition: Term, perms: Term, args: Seq[Term], v: Verifier)
                      : Quantification

  def receiverNonNullAxiom(qvar: Var, cond: Term, rcvr: Term, perms: Term, v: Verifier)
                          : Quantification

  def singletonConditionalPermissions(rcvr: Term, perms: Term): Term

  def createSingletonQuantifiedChunk(rcvr: Term,
                                     field: String,
                                     fvf: Term,
                                     perms: Term)
                                    : QuantifiedFieldChunk

  def createQuantifiedFieldChunk(qvar: Var,
                                 receiver: Term,
                                 field: ast.Field,
                                 fvf: Term,
                                 perms: Term,
                                 condition: Term,
                                 additionalArgs: Seq[Var],
                                 v: Verifier)
                                : (QuantifiedFieldChunk, InverseFunction)

  def permission(h: Heap, receiver: Term, field: ast.Field): Term

  def permission(chs: Seq[QuantifiedFieldChunk], receiver: Term, field: ast.Field): Term

  def withValue(s: State,
                h: Heap,
                field: ast.Field,
                condition: Term,
                receiver: Term,
                pve: PartialVerificationError,
                locacc: ast.LocationAccess,
                v: Verifier)
               (Q: SummarisingFvfDefinition => VerificationResult)
               : VerificationResult

  def splitSingleLocation(s: State,
                          h: Heap,
                          field: ast.Field,
                          receiver: Term, // e
                          perms: Term, // p
                          chunkOrderHeuristic: Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk],
                          v: Verifier)
                         (Q: Option[(State, Heap, QuantifiedFieldChunk, FvfDefinition)] => VerificationResult)
                         : VerificationResult

  def splitLocations(s: State,
                     h: Heap,
                     field: ast.Field,
                     qvar: Some[Var], // x
                     inverseReceiver: Term, // e⁻¹(r)
                     condition: Term, // c(x)
                     receiver: Term, // e(x)
                     perms: Term, // p(x)
                     chunkOrderHeuristic: Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk],
                     v: Verifier)
                     (Q: Option[(State, Heap, QuantifiedFieldChunk, QuantifiedChunkFvfDefinition)] => VerificationResult)
                     : VerificationResult

  def splitHeap(h: Heap, field: String): (Seq[QuantifiedFieldChunk], Seq[Chunk])

  def extractHints(qvar: Option[Var], cond: Option[Term], rcvr: Term): Seq[Term]

  def hintBasedChunkOrderHeuristic(hints: Seq[Term])
                                  : Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk]
}

object quantifiedChunkSupporter extends QuantifiedChunkSupport with Immutable {

  /* Chunk creation */

  def createSingletonQuantifiedChunk(rcvr: Term,
                                     field: String,
                                     fvf: Term,
                                     perms: Term)
                                    : QuantifiedFieldChunk = {

    val condPerms = singletonConditionalPermissions(rcvr, perms)
    val hints = extractHints(None, None, rcvr)

    QuantifiedFieldChunk(field, fvf, condPerms, None, Some(condPerms), Some(rcvr), hints)
  }

  def singletonConditionalPermissions(rcvr: Term, perms: Term): Term = {
    Ite(`?r` === rcvr, perms, NoPerm())
  }


  /** Creates a quantified chunk corresponding to the assertion
    * `forall x: T :: g(x) ==> acc(e(x).f, p(x))`.
    *
    * @param qvar The explicitly quantified variable `x`.
    * @param receiver The receiver expression `e(x)`.
    * @param field The field `f`.
    * @param fvf The field value function that is stored in the chunk to create.
    * @param perms Permission amount `p(x)`.
    * @param condition The condition `g(x)`.
    * @param additionalArgs See the homonymous parameter of [[getFreshInverseFunction]].
    * @return A tuple of
    *           1. the newly created quantified chunk
    *           2. the definitional axioms of the inverse function created for the
    *              chunk, see [[getFreshInverseFunction]].
    */
  def createQuantifiedFieldChunk(qvar: Var,
                                 receiver: Term,
                                 field: ast.Field,
                                 fvf: Term,
                                 perms: Term,
                                 condition: Term,
                                 additionalArgs: Seq[Var],
                                 v: Verifier)
                                : (QuantifiedFieldChunk, InverseFunction) = {

    Predef.assert(fvf.sort.isInstanceOf[sorts.FieldValueFunction],
      s"Quantified chunk values must be of sort FieldValueFunction, but found value $fvf of sort ${fvf.sort}")

    val inverseFunction = getFreshInverseFunction(qvar, receiver, condition, perms, additionalArgs, v)
    val arbitraryInverseRcvr = inverseFunction(`?r`)
    val condPerms = conditionalPermissions(qvar, arbitraryInverseRcvr, condition, perms)
    val ch = QuantifiedFieldChunk(field.name, fvf, condPerms, Some(inverseFunction), Some(condPerms), None, Nil)

    (ch, inverseFunction)
  }

  def conditionalPermissions(qvar: Var, // x
                             inverseReceiver: Term, // e⁻¹(r)
                             condition: Term, // c(x)
                             perms: Term) // p(x)
                            : Term = {

    val conditionOfInv = condition.replace(qvar, inverseReceiver)
    val permsOfInv = perms.replace(qvar, inverseReceiver)

    Ite(conditionOfInv, permsOfInv, NoPerm())
  }

  /* State queries */

  def splitHeap(h: Heap, field: String): (Seq[QuantifiedFieldChunk], Seq[Chunk]) = {
    var quantifiedChunks = Seq[QuantifiedFieldChunk]()
    var otherChunks = Seq[Chunk]()

    h.values foreach {
      case ch: QuantifiedFieldChunk if ch.name == field =>
        quantifiedChunks +:= ch
      case ch: BasicChunk if ch.name == field =>
        sys.error(s"I did not expect non-quantified chunks on the heap for field $field, but found $ch")
      case ch =>
        otherChunks +:= ch
    }

    (quantifiedChunks, otherChunks)
  }

  /**
    * Computes the total permission amount held in the given heap for the given receiver and field.
    */
  def permission(h: Heap, receiver: Term, field: ast.Field): Term = {
    val perms = h.values.toSeq.collect {
      case permChunk: QuantifiedFieldChunk if permChunk.name == field.name =>
        permChunk.perm.replace(`?r`, receiver)
    }

    BigPermSum(perms, Predef.identity)
  }

  def permission(chs: Seq[QuantifiedFieldChunk], receiver: Term, field: ast.Field): Term = {
    val perms = chs map {
      case permChunk: QuantifiedFieldChunk if permChunk.name == field.name =>
        permChunk.perm.replace(`?r`, receiver)
    }

    BigPermSum(perms, Predef.identity)
  }

//  private val withValueCache = MMap[(Term, Set[QuantifiedFieldChunk]), MultiLocationFvf]()

  def withValue(s: State,
                h: Heap,
                field: ast.Field,
                condition: Term,
                receiver: Term,
                pve: PartialVerificationError,
                locacc: ast.LocationAccess,
                v: Verifier)
               (Q: SummarisingFvfDefinition => VerificationResult)
               : VerificationResult = {

    v.decider.assert(PermLess(NoPerm(), permission(h, receiver, field))) {
      case false =>
        failure(pve dueTo InsufficientPermission(locacc), v)

      case true =>
        val (quantifiedChunks, _) = splitHeap(h, field.name)
        val additionalFvfArgs = s.functionRecorder.data.fold(Seq.empty[Var])(_.arguments)
        val fvf = freshFVF(field, isChunkFvf = false, additionalFvfArgs, v)
        val fvfDef = SummarisingFvfDefinition(field, fvf, receiver, quantifiedChunks)

        /* Optimisisations */

//            val cacheLog = bookkeeper.logfiles("withValueCache")
//            cacheLog.println(s"receiver = $receiver")
//            cacheLog.println(s"lookupRcvr = $lookupRcvr")
//            cacheLog.println(s"consideredCunks = $consideredCunks")
//            cacheLog.println(s"fvf = $fvf")
//            cacheLog.println(s"fvfDefs.length = ${fvfDefs.length}")
//            cacheLog.println(s"fvfDefs = $fvfDefs")

        val fvfDefToReturn =
          /* TODO: Reusing the fvf found in a single entry is only sound if
           * the current g(x) (should be known at call-site of withValue)
           * and the g(x) from the entry are the same. Detecting this
           * syntactically is not always possible since i1 <= inv1(r) < j1
           * might be logically equivalent to i2 <= inv2(r) < j2, but
           * syntactically it obviously isn't. Creating a single
           * inv-function per range and receiver might help, though.
           */
          /*if (fvfDef.entries.length == 1) {
            val fvfDefEntry = fvfDef.entries.head
            val _fvf = fvfDefEntry.partialDomain.fvf
            val _lookupRcvr = lookupRcvr.copy(fvf = fvfDefEntry.partialDomain.fvf)
//                val _fvfDef = FvfDef(field, _fvf, false, fvfDefEntry.copy(True(), Nil) :: Nil)
            val _fvfDef = FvfDef(field, _fvf, false, Nil)

            (_lookupRcvr, _fvfDef)
          } else */{
//                if (config.disableQPCaching())
            fvfDef
//                else {
//                  /* TODO: Caching needs to take the branch conditions into account! */
//                  cacheLog.println(s"cached? ${withValueCache.contains(receiver, consideredCunks)}")
//                  withValueCache.getOrElseUpdate((receiver, toSet(quantifiedChunks)), fvfDef)
//                }
          }

//            cacheLog.println(s"lookupRcvrToReturn = $lookupRcvrToReturn")
//            cacheLog.println(s"fvfDefToReturn = $fvfDefToReturn")
//            cacheLog.println()

        /* We're done */

        Q(fvfDefToReturn)}
  }

  /* Manipulating quantified chunks */

  def splitSingleLocation(s: State,
                          h: Heap,
                          field: ast.Field,
                          receiver: Term, // e
                          perms: Term, // p
                          chunkOrderHeuristic: Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk],
                          v: Verifier)
                         (Q: Option[(State, Heap, QuantifiedFieldChunk, FvfDefinition)] => VerificationResult)
                         : VerificationResult = {

    val (s1, h1, ch, fvfDef, success) =
      split(s, h, field, None, `?r`, `?r` === receiver, receiver, perms, chunkOrderHeuristic, v)

    if (success) {
      Q(Some(s1, h1, ch, fvfDef))
    } else
      Q(None)
  }

  def splitLocations(s: State,
                     h: Heap,
                     field: ast.Field,
                     qvar: Some[Var], // x
                     inverseReceiver: Term, // e⁻¹(r)
                     condition: Term, // c(x)
                     receiver: Term, // e(x)
                     perms: Term, // p(x)
                     chunkOrderHeuristic: Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk],
                     v: Verifier)
                    (Q: Option[(State, Heap, QuantifiedFieldChunk, QuantifiedChunkFvfDefinition)] => VerificationResult)
                    : VerificationResult = {

    val (s1, h1, ch, fvfDef, success) =
      split(s, h, field, qvar, inverseReceiver, condition, receiver, perms, chunkOrderHeuristic, v)

    if (success) {
      Q(Some(s1, h1, ch, fvfDef.asInstanceOf[QuantifiedChunkFvfDefinition]))
    } else
      Q(None)
  }

  private def split(s: State,
                    h: Heap,
                    field: ast.Field,
                    qvar: Option[Var], // x
                    inverseReceiver: Term, // e⁻¹(r)
                    condition: Term, // c(x)
                    receiver: Term, // e(x)
                    perms: Term, // p(x)
                    chunkOrderHeuristic: Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk],
                    v: Verifier)
                   : (State, Heap, QuantifiedFieldChunk, FvfDefinition, Boolean) = {

    val (quantifiedChunks, otherChunks) = splitHeap(h, field.name)
    val candidates = if (Verifier.config.disableChunkOrderHeuristics()) quantifiedChunks else chunkOrderHeuristic(quantifiedChunks)
    val pInit = qvar.fold(perms)(x => perms.replace(x, inverseReceiver)) // p(e⁻¹(r))
    val conditionOfInv = qvar.fold(condition)(x => condition.replace(x, inverseReceiver)) // c(e⁻¹(r))
    val conditionalizedPermsOfInv = Ite(conditionOfInv, pInit, NoPerm()) // c(e⁻¹(r)) ? p_init(r) : 0

    var residue: List[Chunk] = Nil
    var pNeeded = pInit
    var success = false

    /* TODO: Is the following comment this valid?
     *
     * Using inverseReceiver instead of receiver yields axioms
     * about the summarising fvf where the inverse function occurring in
     * inverseReceiver is part of the axiom trigger. This makes several
     * examples fail, including issue_0122.sil, because assertions in the program
     * that talk about concrete receivers will not use the inverse function, and
     * thus will not trigger the axioms that define the values of the fvf.
     */
    val functionAxiomatizationArguments = s.functionRecorder.data.fold(Seq.empty[Var])(_.arguments)
    val fvfDef =
      if (qvar.isEmpty) {
        val additionalFvfArgs = functionAxiomatizationArguments ++ (s.quantifiedVariables filter receiver.contains)
        val fvf = freshFVF(field, isChunkFvf = true, additionalFvfArgs, v)
        SingletonChunkFvfDefinition(field, fvf, receiver, Right(candidates) /*, true*/)(v.triggerGenerator, v.axiomRewriter)
      } else {
        val fvf = freshFVF(field, isChunkFvf = true, functionAxiomatizationArguments, v)
        QuantifiedChunkFvfDefinition(field, fvf, qvar.toSeq, Ite(condition, perms, NoPerm()), receiver, candidates /*, true*/)(v.triggerGenerator, v.axiomRewriter)
      }

    v.decider.prover.comment(s"Precomputing split data for $receiver.${field.name} # $perms")

    val precomputedData = candidates map { ch =>
      val pTaken = Ite(conditionOfInv, PermMin(ch.perm, pNeeded), NoPerm())
      val permsTakenFunc = v.decider.freshMacro("pTaken", `?r` :: Nil, pTaken)
      val permsTakenFApp = (t: Term) => App(permsTakenFunc, t :: Nil)

      pNeeded = PermMinus(pNeeded, permsTakenFApp(`?r`))

      (ch, permsTakenFApp(`?r`), pNeeded)
    }

    v.decider.prover.comment(s"Done precomputing, updating quantified heap chunks")

    var tookEnough = Forall(`?r`, Implies(conditionOfInv, pNeeded === NoPerm()), Nil: Seq[Trigger])

    precomputedData foreach { case (ithChunk, ithPTaken, ithPNeeded) =>
      if (success)
        residue ::= ithChunk
      else {
        val constrainPermissions = !consumeExactRead(perms, s.constrainableARPs)

        val (permissionConstraint, depletedCheck) =
          createPermissionConstraintAndDepletedCheck(qvar, conditionalizedPermsOfInv,
                                                     constrainPermissions, ithChunk,
                                                     ithPTaken, v)

        if (constrainPermissions) {
          v.decider.prover.comment(s"Constrain original permissions $perms")
          v.decider.assume(permissionConstraint)

          residue ::= ithChunk.copy(perm = PermMinus(ithChunk.perm, ithPTaken))
        } else {
          v.decider.prover.comment(s"Chunk depleted?")
          val chunkDepleted = v.decider.check(depletedCheck, Verifier.config.splitTimeout())

          if (!chunkDepleted) residue ::= ithChunk.copy(perm = PermMinus(ithChunk.perm, ithPTaken))
        }

        /* The success-check inside this loop is done with a (short) timeout.
         * Outside of the loop, the last success-check (potentially) needs to be
         * re-done, but without a timeout. In order to make this possible,
         * the assertion to check is recorded by tookEnough.
         */
        tookEnough = Forall(`?r`, Implies(conditionOfInv, ithPNeeded === NoPerm()), Nil: Seq[Trigger])

        v.decider.prover.comment(s"Enough permissions taken?")
        success = v.decider.check(tookEnough, Verifier.config.splitTimeout())
      }
    }

    v.decider.prover.comment("Final check that enough permissions have been taken")
    success = success || v.decider.check(tookEnough, 0) /* This check is a must-check, i.e. an assert */

    v.decider.prover.comment("Done splitting")

    val hResidue = Heap(residue ++ otherChunks)
    val chunkSplitOf = QuantifiedFieldChunk(field.name, fvfDef.fvf, conditionalizedPermsOfInv, None, None, None, Nil)

    (s, hResidue, chunkSplitOf, fvfDef, success)
  }

  private def createPermissionConstraintAndDepletedCheck(qvar: Option[Var], // x
                                                         conditionalizedPermsOfInv: Term, // c(e⁻¹(r)) ? p_init(r) : 0
                                                         constrainPermissions: Boolean,
                                                         ithChunk: QuantifiedFieldChunk,
                                                         ithPTaken: Term,
                                                         v: Verifier)
                                                        : (Term, Term) = {

    val result = eliminateImplicitQVarIfPossible(ithChunk.perm, qvar)

    val permissionConstraint =
      if (constrainPermissions)
        result match {
          case None =>
            val q1 = Forall(`?r`,
                       Implies(
                         ithChunk.perm !== NoPerm(),
                         PermLess(conditionalizedPermsOfInv, ithChunk.perm)),
                       Nil: Seq[Trigger], s"qp.srp${v.counter(this).next()}")

            if (Verifier.config.disableISCTriggers()) q1 else v.quantifierSupporter.autoTrigger(q1)

          case Some((perms, singleRcvr)) =>
            Implies(
              perms !== NoPerm(),
              PermLess(conditionalizedPermsOfInv.replace(`?r`, singleRcvr), perms))
        }
      else
        True()

    val depletedCheck = result match {
      case None =>
        Forall(`?r`, PermMinus(ithChunk.perm, ithPTaken) === NoPerm(), Nil: Seq[Trigger])
      case Some((perms, singleRcvr)) =>
        PermMinus(perms, ithPTaken.replace(`?r`, singleRcvr)) === NoPerm()
    }

    (permissionConstraint, depletedCheck)
  }

  @inline
  private def eliminateImplicitQVarIfPossible(perms: Term, qvar: Option[Var])
                                             : Option[(Term, Term)] = {

    /* TODO: This code could be improved significantly if we
     *         - distinguished between quantified chunks for single and multiple locations
     *         - separated the initial permission amount from the subtracted amount(s) in each chunk
     */

    /* This method essentially tries to detect if a quantified chunk provides
     * permissions to a single location only, in which case there isn't a need
     * to create, e.g. permission constraints or depleted checks that quantify
     * over the implicit receiver (i.e. over r).
     *
     * With the current approach to handling quantified permissions, a
     * quantified chunk that provides permissions to a single receiver only
     * will have a permission term (chunk.perm) of the shape
     *   (r == t ? p(r) : 0) - p_1(r) - ... - p_n(r)
     * The conditional represents the initial permission amount that the chunk
     * was initialised with, and the p_i(r) are amounts that have potentially
     * been split of during the execution (by construction, it is ensured that
     * the term is >= 0).
     *
     * Quantifying over r is not relevant for such terms, because the only
     * meaningful choice of r is t. Hence, such terms are rewritten to
     *   p(t) - p_1(t) - ... - p_n(t)
     *
     * I benchmarked the effects of this optimisation on top of Silicon-QP
     * revision 0bc3d0d81890 (2015-08-11), and the runtime didn't change.
     * However, in the case of constraining symbolic permissions, the
     * optimisation will avoid assuming foralls for which no triggers can be
     * found. These cases are rather rare (at the moment of writing, about 10
     * for the whole test suite), but probably still worth avoiding.
     */

    var v: Term = `?r`

    def eliminateImplicitQVarIfPossible(t: Term): Term = t.transform {
      case Ite(Equals(`?r`, w), p1, NoPerm()) if !qvar.exists(w.contains) =>
        v = w
        p1.replace(`?r`, v)
      case pm @ PermMinus(t1, t2) =>
        /* By construction, the "subtraction tree" should be left-leaning,
         * with the initial permission amount (the conditional) as its
         * left-most term.
         */
        val s1 = eliminateImplicitQVarIfPossible(t1)
        if (v == `?r`) pm
        else PermMinus(s1, t2.replace(`?r`, v))
      case other =>
        other
    }()

    val result = eliminateImplicitQVarIfPossible(perms)

    if (v == `?r`) None
    else Some((result, v))
  }

  /* Misc */

  /* ATTENTION: Never create an FVF without calling this method! */
  private def freshFVF(field: ast.Field, isChunkFvf: Boolean, appliedArgs: Seq[Term], v: Verifier)
                      : Term = {

//    bookkeeper.logfiles("fvfs").println(s"isChunkFvf = $isChunkFvf")

    val freshFvf =
      if (isChunkFvf) {
        val fvfSort = sorts.FieldValueFunction(v.symbolConverter.toSort(field.typ))
        val freshFvf = v.decider.appliedFresh("fvf#part", fvfSort, appliedArgs)

        freshFvf
      } else {
        val fvfSort = sorts.FieldValueFunction(v.symbolConverter.toSort(field.typ))
        val freshFvf = v.decider.appliedFresh("fvf#tot", fvfSort, appliedArgs)

        freshFvf
      }

    freshFvf
  }


  /** @inheritdoc */
  def createSingletonFieldValueFunction(s: State,
                                        field: ast.Field,
                                        rcvr: Term,
                                        value: Term,
                                        v: Verifier)
                                       : (Term, Option[SingletonChunkFvfDefinition]) = {

    val additionalFvfArgs = (
         s.functionRecorder.data.fold(Seq.empty[Var])(_.arguments)
      ++ s.quantifiedVariables filter rcvr.contains)

    value.sort match {
      case _: sorts.FieldValueFunction =>
        sys.error("Seems that this code is reachable after all ...")
//        /* The value is already a field value function, in which case we don't create a fresh one. */
//        assert(additionalFvfArgs.isEmpty) /* TODO: How to proceed if non-empty? */
//        (value, None)

      case _ =>
        val fvf = freshFVF(field, isChunkFvf = true, additionalFvfArgs, v)
        val fvfDef = SingletonChunkFvfDefinition(field, fvf, rcvr, Left(value))(v.triggerGenerator, v.axiomRewriter)

        (fvf, Some(fvfDef))
    }
  }

  def domainDefinitionAxioms(field: ast.Field, qvar: Var, cond: Term, rcvr: Term, fvf: Term, inv: InverseFunction) = {
    val axioms = cond match {
      case SetIn(`qvar`, set) if rcvr == qvar =>
        /* Optimised axiom in the case where the quantified permission forall is of the
         * shape "forall x :: x in set ==> acc(x.f)".
         */
        Seq(Domain(field.name, fvf) === set)

      case _ => Seq(
        /* Create an axiom of the shape "forall x :: x in domain(fvf) <==> cond(x)" */
        /* TODO: Unify with MultiLocationFvf.domainDefinition */
        /* TODO: Why does this axiom not use `?r` and inv? */
        Forall(qvar,
          Iff(
            SetIn(rcvr, Domain(field.name, fvf)),
            cond),
//          Trigger(Lookup(field.name, fvf, receiver)))
          if (Verifier.config.disableISCTriggers()) Nil: Seq[Trigger] else Trigger(SetIn(rcvr, Domain(field.name, fvf))) :: Nil,
          s"qp.$fvf-dom")
        /* Create an axiom of the shape "forall r :: r in domain(fvf) ==> cond[x |-> inv(r)]" */
//        Forall(`?r`,
//          Implies(
//            SetIn(`?r`, Domain(field.name, fvf)),
//            And(
//              cond.replace(qvar, inv(`?r`)),
//              receiver.replace(qvar, inv(`?r`)) === `?r`)),
//          Trigger(SetIn(`?r`, Domain(field.name, fvf))))
      )
    }

    //    val log = bookkeeper.logfiles("domainDefinitionAxiom")
    //    log.println(s"axiom = $axiom")

    axioms
  }

  def injectivityAxiom(qvars: Seq[Var], condition: Term, perms: Term, args: Seq[Term], v: Verifier)= {
    val qvars1 = qvars.map(qvar => v.decider.fresh(qvar.id.name, qvar.sort))
    val qvars2 = qvars.map(qvar => v.decider.fresh(qvar.id.name, qvar.sort))

    val effectiveCondition = And(condition, PermLess(NoPerm(), perms))
    val cond1 = effectiveCondition.replace(qvars, qvars1)
    val cond2 = effectiveCondition.replace(qvars, qvars2)
    val args1 = args.map(_.replace(qvars, qvars1))
    val args2 = args.map(_.replace(qvars, qvars2))

    val argsEqual = (args1 zip args2).map(argsRenamed =>  argsRenamed._1 === argsRenamed._2).reduce((a1, a2) => And(a1, a2))
    val varsEqual = (qvars1 zip qvars2).map(vars => vars._1 === vars._2).reduce((v1, v2) => And(v1, v2) )

    val implies =
      Implies(
        And(cond1,
          cond2,
          argsEqual),
        varsEqual)

    Forall(
      qvars1 ++ qvars2,
      implies,
      Nil,
      /* receiversEqual :: And(effectiveCondition.replace(qvar, vx), effectiveCondition.replace(qvar, vy)) :: Nil */
      s"qp.inj${v.counter(this).next()}")
  }

  def receiverNonNullAxiom(qvar: Var, cond: Term, rcvr: Term, perms: Term, v: Verifier) = {
    val q1 =
      Forall(
        qvar,
        Implies(And(cond, PermLess(NoPerm(), perms)), rcvr !== Null()),
        Nil,
        s"qp.null${v.counter(this).next()}")
    val axRaw = if (Verifier.config.disableISCTriggers()) q1 else v.quantifierSupporter.autoTrigger(q1)

    val ax = v.axiomRewriter.rewrite(axRaw).getOrElse(axRaw)

    ax
  }

  /** Creates a fresh inverse function `inv` and returns the function as well as the
    * definitional axioms.
    *
    * @param qvar A variable (most likely bound by a forall) that occurs in `fct`
    *             and that is the result of the inverse function applied to `fct`,
    *             i.e. `inv(fct) = qvar` (if `condition` holds).
    * @param fct A term containing the variable `qvar` that can be understood as
    *           the application of an invertible function to `qvar`.
    * @param condition A condition (containing `qvar`) that must hold in order for
    *                  `inv` to invert `fct` to `qvar`.
    * @param perms A permission term (containing `qvar`) that must denote non-none
    *              permission in order for `inv` to invert `fct` to `qvar`.
    * @param additionalInvArgs Additional arguments on which `inv` depends.
    * @return A tuple of
    *           1. the inverse function as a function of a single arguments (the
    *              `additionalArgs` have been fixed already)
    *           2. the definitional axioms of the inverse function.
    */
  def getFreshInverseFunction(qvar: Var,
                              fct: Term,
                              condition: Term,
                              perms: Term,
                              additionalInvArgs: Seq[Var],
                              v: Verifier)
                             : InverseFunction = {

    Predef.assert(fct.sort == sorts.Ref, s"Expected ref-sorted term, but found $fct of sort ${fct.sort}")

    val func = v.decider.fresh("inv", (additionalInvArgs map (_.sort)) :+ fct.sort, qvar.sort)
    val inverseFunc = (t: Term) => App(func, additionalInvArgs :+ t)
    val invOfFct: Term = inverseFunc(fct)
    val fctOfInv = fct.replace(qvar, inverseFunc(`?r`))
    val effectiveCondition = And(condition, PermLess(NoPerm(), perms))
    val effectiveConditionInv = effectiveCondition.replace(qvar, inverseFunc(`?r`))

    val finalAxInvOfFct =
      v.triggerGenerator.assembleQuantification(
        Forall,
        qvar :: Nil,
        Implies(effectiveCondition, invOfFct === qvar),
        if (Verifier.config.disableISCTriggers()) Nil: Seq[Term] else fct :: And(effectiveCondition, invOfFct) :: Nil,
        s"qp.${func.id}-exp",
        v.axiomRewriter)

    val finalAxFctOfInv =
      v.triggerGenerator.assembleQuantification(
        Forall,
        `?r` :: Nil,
        Implies(effectiveConditionInv, fctOfInv === `?r`),
        if (Verifier.config.disableISCTriggers()) Nil: Seq[Trigger] else Trigger(inverseFunc(`?r`)) :: Nil,
        s"qp.${func.id}-imp",
        v.axiomRewriter)

    InverseFunction(func, inverseFunc, finalAxInvOfFct, finalAxFctOfInv)
  }


  def hintBasedChunkOrderHeuristic(hints: Seq[Term])
                                  : Seq[QuantifiedFieldChunk] => Seq[QuantifiedFieldChunk] =

    (chunks: Seq[QuantifiedFieldChunk]) => {
      val (matchingChunks, otherChunks) = chunks.partition(_.hints == hints)

      matchingChunks ++ otherChunks
    }

  def extractHints(qvar: Option[Var], cond: Option[Term], rcvr: Term): Seq[Term] = {
    None.orElse(rcvr.find{case SeqAt(seq, _) => seq})
        .orElse(cond.flatMap(_.find { case SeqIn(seq, _) => seq; case SetIn(_, set) => set }))
        .toSeq
  }
}
