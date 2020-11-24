/*
 * Copyright (C) 2016  Nikos Katzouris
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package trail.learning.online.oled

import trail.app.runutils.{Example, RunningOptions}
import trail.app.runutils.InputHandling.InputSource
import trail.inference.{ASPSolver, Inference}
import trail.learning.online.Types.StartOver
import trail.learning.online.OnlineLearner
import trail.learning.structure.{OldStructureLearningFunctions, RuleExpansion}
import trail.learning.online.woledmln.WoledMLNLearnerUtils
import trail.learning.utils
import trail.learning.utils.LearnUtils
import trail.logic.{Clause, Literal, LogicUtils}

/**
  * Created by nkatz at 12/2/20
  *
  * Implements the abstract methods of the abstract parent class, to learn crisp ASP rules in n onlne fashion.
  *
  * This is an implementation of the original OLED algorithm.
  */

class OLEDLearner[T <: InputSource](inps: RunningOptions, trainingDataOptions: T,
    testingDataOptions: T, trainingDataFunction: T => Iterator[Example],
    testingDataFunction: T => Iterator[Example]) extends OnlineLearner(inps, trainingDataOptions, testingDataOptions, trainingDataFunction, testingDataFunction) {

  def process(exmpl: Example) = {

    val inferredState = Map.empty[String, Boolean]
    var tpCounts = 0
    var fpCounts = 0
    var fnCounts = 0
    var totalGroundings = 0
    var rulesCompressed = List.empty[Clause]
    var inferenceTime = 0.0
    var scoringTime = 0.0

    //rulesCompressed = state.getBestRules(inps.globals, "score") .filter(_.precision >= 0.8)
    //rulesCompressed = state.getAllRules(inps, "top")
    rulesCompressed = state.getTopTheory().filter(x => x.body.nonEmpty && x.precision >= inps.pruneThreshold)

    if (rulesCompressed.nonEmpty) {
      val inferredState = ASPSolver.crispLogicInference(rulesCompressed, exmpl, inps.globals)
      val (_tpCounts, _fpCounts, _fnCounts, _totalGroundings, _inertiaAtoms) =
        WoledMLNLearnerUtils.scoreAndUpdateWeights(exmpl, inferredState, state.getAllRules("all").toVector, inps, logger)
      tpCounts = _tpCounts
      fpCounts = _fpCounts
      fnCounts = _fnCounts
      totalGroundings = _totalGroundings
      inertiaAtoms = _inertiaAtoms.toSet
    } else {
      fnCounts = exmpl.queryAtoms.size
    }

    updateStats(tpCounts, fpCounts, fnCounts, totalGroundings)

    this.inertiaAtoms = inertiaAtoms
    this.inertiaAtoms = Set.empty[Literal] // Use this to difuse inertia

    logger.info(batchInfoMsg(rulesCompressed, tpCounts, fpCounts, fnCounts, inferenceTime, scoringTime))

    if (!withHandCrafted) {
      var newInit = List.empty[Clause]
      var newTerm = List.empty[Clause]

      if (fpCounts > 0 || fnCounts > 0) {

        /*if (!inps.weightLean) {
          val topInit = state.initiationRules.filter(_.body.nonEmpty)
          val topTerm = state.terminationRules.filter(_.body.nonEmpty)
          val growNewInit = OldStructureLearningFunctions.growNewRuleTest(topInit, e, inps.globals, "initiatedAt")
          val growNewTerm = OldStructureLearningFunctions.growNewRuleTest(topTerm, e, inps.globals, "terminatedAt")
          //newInit = if (growNewInit) OldStructureLearningFunctions.generateNewRulesOLED(topInit, e, "initiatedAt", inps.globals) else Nil
          //newTerm = if (growNewTerm) OldStructureLearningFunctions.generateNewRulesOLED(topTerm, e, "terminatedAt", inps.globals) else Nil
          newInit = OldStructureLearningFunctions.generateNewRulesOLED(topInit, e, "initiatedAt", inps.globals) //if (growNewInit) generateNewRules(topInit, e, "initiatedAt", inps.globals) else Nil
          newTerm = OldStructureLearningFunctions.generateNewRulesOLED(topTerm, e, "terminatedAt", inps.globals) //if (growNewTerm) generateNewRules(topTerm, e, "terminatedAt", inps.globals) else Nil
        }*/

        //This is the "correct one" so far.
        val theory = rulesCompressed
        val newRules = OldStructureLearningFunctions.generateNewRules(theory, exmpl, inps)
        //val newRules = OldStructureLearningFunctions.generateNewRules(Nil, exmpl, inps)
        val (init, term) = newRules.partition(x => x.head.predSymbol == "initiatedAt")

        newInit = init
        newTerm = term

        val allNew = newInit ++ newTerm
        if (allNew.nonEmpty) WoledMLNLearnerUtils.showNewRulesMsg(fpCounts, fnCounts, allNew, logger)
        //mergeAndUpdate(allNew)
        state.updateRules(newInit ++ newTerm, "add")

      }

      val newRules = newInit ++ newTerm

      // score the new rules and update their weights
      val newRulesWithRefs = newRules.flatMap(x => x.refinements :+ x).toVector
      WoledMLNLearnerUtils.scoreAndUpdateWeights(exmpl, inferredState, newRulesWithRefs, inps, logger, newRules = true)

      /* Rules' expansion. */
      // We only need the top rules for expansion here.
      val init = state.initiationRules
      val term = state.terminationRules
      val expandedTheory = RuleExpansion.expandRules(init ++ term, inps, logger)

      state.updateRules(expandedTheory._1, "replace")

      //val pruningSpecs = new PruningSpecs(0.8, 2, 100)
      //val pruned = state.pruneRules(pruningSpecs, inps, logger)
    }
  }

  def generateNewRules(existingTheory: List[Clause], ex: Example, in: RunningOptions) = {
    generateNewRulesConservative(existingTheory, ex, inps)
    //generateNewRulesEager(existingTheory, ex, inps)
  }

  /**
    * Generates new rules by (minimally) abducing new rule heads from the data, using the
    * existing rules in the theory to avoid abducing redundant atoms.
    */
  def generateNewRulesConservative(existingTheory: List[Clause], ex: Example, in: RunningOptions) = {
    OldStructureLearningFunctions.generateNewRules(existingTheory, ex, inps)
  }

  /**
    * Generates new rules directly from the commited mistakes.
    * This method does not actually use the existing theory.
    */
  def generateNewRulesEager(existingTheory: List[Clause], ex: Example, in: RunningOptions) = {
    val topInit = state.initiationRules.filter(_.body.nonEmpty)
    val topTerm = state.terminationRules.filter(_.body.nonEmpty)
    //val growNewInit = OldStructureLearningFunctions.growNewRuleTest(topInit, ex, inps.globals, "initiatedAt")
    //val growNewTerm = OldStructureLearningFunctions.growNewRuleTest(topTerm, ex, inps.globals, "terminatedAt")
    //newInit = if (growNewInit) OldStructureLearningFunctions.generateNewRulesOLED(topInit, e, "initiatedAt", inps.globals) else Nil
    //newTerm = if (growNewTerm) OldStructureLearningFunctions.generateNewRulesOLED(topTerm, e, "terminatedAt", inps.globals) else Nil
    val newInit = OldStructureLearningFunctions.generateNewRulesOLED(topInit, ex, "initiatedAt", inps.globals) //if (growNewInit) generateNewRules(topInit, e, "initiatedAt", inps.globals) else Nil
    val newTerm = OldStructureLearningFunctions.generateNewRulesOLED(topTerm, ex, "terminatedAt", inps.globals) //if (growNewTerm) generateNewRules(topTerm, e, "terminatedAt", inps.globals) else Nil
    newInit ++ newTerm
  }

  /**
    * For each generated new rule r, either merge its support with an existing r', such that r' subsumes r,
    * or add r' to the current top theory (update the state).
    */
  def mergeAndUpdate(newRules: List[Clause]) = {
    val topRules = state.getTopTheory().filter(_.body.nonEmpty)

    val actuallyNewRules = newRules.foldLeft(Vector.empty[Clause]) { (accum, newRule) =>
      var merge = false
      topRules foreach { topRule =>
        /*if (topRule.thetaSubsumes(newRule)) {
          // Just merge the support sets and generate refinements again.
          topRule.supportSet = topRule.supportSet ++ newRule.supportSet
          topRule.generateCandidateRefs(inps.specializationDepth, inps.globals.comparisonPredicates)
          logger.info(s"\nNew rule:\n  ${newRule.tostring}\n  with support:\n  " +
            s"${newRule.supportSet.map(_.tostring).mkString("\n")} merged with existing rule:\n  ${topRule.tostring}")
          merge = true
        }*/

        /**
          * Do this only for rules with a non-empty body, its too dangerous to merge ones
          * with empty body cause many interesting stuff may be missed
          */
        if (newRule.body.nonEmpty && newRule.thetaSubsumes(topRule)) {
          // Just merge the support sets and generate refinements again.
          val newBottomRules = newRule.supportSet.filter(topRule.thetaSubsumes)
          if (newBottomRules.nonEmpty) {
            topRule.supportSet = topRule.supportSet ++ newRule.supportSet
            if (inps.ruleLearningStrategy == "hoeffding") {
              topRule.generateCandidateRefs(inps.specializationDepth, inps.globals.comparisonPredicates)
            }
          }
          logger.info(s"\nNew rule:\n  ${newRule.tostring}\n  with support:\n  " +
            s"${newRule.supportSet.map(_.tostring).mkString("\n")} merged with existing rule:\n  ${topRule.tostring}")
          merge = true
        }

      }
      if (merge) accum else accum :+ newRule
    }

    actuallyNewRules foreach { newRule =>
      logger.info(s"\nCreated new rule:\n  ${newRule.tostring}\n  with support:\n  " +
        s"${newRule.supportSet.map(_.tostring).mkString("\n")}")
      state.updateRules(List(newRule), "add")
    }
  }

  /**
    * Prints statistics & evaluates on test set (if one provided)
    */
  def wrapUp() = {
    logger.info(s"\nFinished the data")
    if (repeatFor > 0) {
      self ! new StartOver
    } else if (repeatFor == 0) {
      val theory = state.getTopTheory() //state.getAllRules("top")

      showStats(theory)

      if (trainingDataOptions != testingDataOptions) { // test set given, eval on that
        val finalRules = rescore()
        //val finalRules = theory
        logger.info(s"\nTheory after pruning:\n${LogicUtils.showTheoryWithStats(finalRules, inps.scoringFun, inps.weightLean)}")
        val testData = testingDataFunction(testingDataOptions)
        evalOnTestSet(testData, finalRules, inps)
      }

      shutDown()

    } else { // Just to be on the safe side...
      throw new RuntimeException("This should never have happened (repeatFor is negative).")
    }
  }

  def evalOnTestSet(testData: Iterator[Example], rules: List[Clause], inps: RunningOptions) = {
    val tester = new Inference(testData, rules, inps, false)
    tester.testTheory
  }

  /**
    * Go over the data and update each rule's score (for cross-validation final rules are selected
    * from those whose score exceeds a threshold)
    */
  def rescore() = {
    logger.info("Eval on test set")
    val data = trainingDataFunction(trainingDataOptions)
    val rules = trail.logic.LogicUtils.compressTheory(state.getTopTheory().filter(_.body.nonEmpty))
    rules foreach (_.clearStatistics)
    data foreach { batch =>
      val inferredState = ASPSolver.crispLogicInference(rules, batch, inps.globals)
      //WoledMLNLearnerUtils.scoreAndUpdateWeights(batch, inferredState, state.getAllRules(inps, "all").toVector, inps, logger)
      WoledMLNLearnerUtils.scoreAndUpdateWeights(batch, inferredState, rules.toVector, inps, logger)
    }
    rules.filter(x => x.precision >= inps.pruneThreshold)
  }

}
