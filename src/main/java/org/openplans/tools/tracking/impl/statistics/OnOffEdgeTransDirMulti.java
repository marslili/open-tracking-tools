package org.openplans.tools.tracking.impl.statistics;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.math.matrix.mtj.DenseVector;
import gov.sandia.cognition.statistics.bayesian.conjugate.MultinomialBayesianEstimator;
import gov.sandia.cognition.statistics.distribution.DirichletDistribution;
import gov.sandia.cognition.statistics.distribution.MultinomialDistribution;
import gov.sandia.cognition.statistics.distribution.MultivariatePolyaDistribution;
import gov.sandia.cognition.util.AbstractCloneableSerializable;

import java.util.List;
import java.util.Random;

import org.apache.commons.lang.builder.CompareToBuilder;
import org.openplans.tools.tracking.impl.graph.InferredEdge;
import org.openplans.tools.tracking.impl.util.OtpGraph;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Class representing the transition from one edge to another. For now we use
 * three transition types: 1. off-road to off-road/on-road to on-road 2.
 * off-road to on-road 3. on-road to off-road
 * 
 * @author bwillard
 * 
 */
public class OnOffEdgeTransDirMulti extends
    AbstractCloneableSerializable implements
    Comparable<OnOffEdgeTransDirMulti> {

  private static final long serialVersionUID = -8329433263373783485L;

  /*
   * Distribution corresponding to free-movement -> free-movement and
   * free-movement -> edge-movement
   */
  private DirichletDistribution freeMotionTransProbPrior;
  private MultinomialDistribution freeMotionTransPrior =
      new MultinomialDistribution(2, 1);
  MultinomialBayesianEstimator freeMotionTransEstimator =
      new MultinomialBayesianEstimator(getFreeMotionTransPrior(),
          getFreeMotionTransProbPrior());

  /*
   * Distribution corresponding to edge-movement -> free-movement and
   * edge-movement -> edge-movement
   */
  private DirichletDistribution edgeMotionTransProbPrior;
  private MultinomialDistribution edgeMotionTransPrior =
      new MultinomialDistribution(2, 1);
  MultinomialBayesianEstimator edgeMotionTransEstimator =
      new MultinomialBayesianEstimator(getFreeMotionTransPrior(),
          getFreeMotionTransProbPrior());

  private final OtpGraph graph;

  private static final Vector stateOffToOff = VectorFactory
      .getDefault().copyValues(1d, 0d);
  private static final Vector stateOffToOn = VectorFactory
      .getDefault().copyValues(0d, 1d);

  private static final Vector stateOnToOn = VectorFactory
      .getDefault().copyValues(1d, 0d);
  private static final Vector stateOnToOff = VectorFactory
      .getDefault().copyValues(0d, 1d);

  /**
   * Initialize prior from the given hyper prior parameters, using the hyper
   * prior means (i.e. the given parameters).
   * 
   * @param graph
   * @param edgeMotionPriorParams
   * @param freeMotionPriorParams
   * @param rng
   */
  public OnOffEdgeTransDirMulti(OtpGraph graph,
    Vector edgeMotionPriorParams, Vector freeMotionPriorParams) {
    this.graph = graph;
    this.setFreeMotionTransProbPrior(new DirichletDistribution(
        freeMotionPriorParams));
    this.setEdgeMotionTransProbPrior(new DirichletDistribution(
        edgeMotionPriorParams));
    /*
     * Start by setting the means.
     */
    getFreeMotionTransPrior().setParameters(
        getFreeMotionTransProbPrior().getMean());
    getEdgeMotionTransPrior().setParameters(
        getEdgeMotionTransProbPrior().getMean());
  }

  @Override
  public OnOffEdgeTransDirMulti clone() {
    final OnOffEdgeTransDirMulti transDist =
        (OnOffEdgeTransDirMulti) super.clone();
    transDist.edgeMotionTransEstimator =
        (MultinomialBayesianEstimator) this.edgeMotionTransEstimator
            .clone();
    transDist.freeMotionTransEstimator =
        (MultinomialBayesianEstimator) this.freeMotionTransEstimator
            .clone();
    transDist.setEdgeMotionTransPrior(this.getEdgeMotionTransPrior()
        .clone());
    transDist.setFreeMotionTransPrior(this.getFreeMotionTransPrior()
        .clone());
    transDist.setEdgeMotionTransProbPrior(this
        .getEdgeMotionTransProbPrior().clone());
    transDist.setFreeMotionTransProbPrior(this
        .getFreeMotionTransProbPrior().clone());

    return transDist;
  }

  @Override
  public int compareTo(OnOffEdgeTransDirMulti o) {
    final CompareToBuilder comparator = new CompareToBuilder();
    comparator.append(((DenseVector) this.getEdgeMotionTransPrior()
        .getParameters()).getArray(), ((DenseVector) o
        .getEdgeMotionTransPrior().getParameters()).getArray());
    comparator.append(((DenseVector) this.getFreeMotionTransPrior()
        .getParameters()).getArray(), ((DenseVector) o
        .getFreeMotionTransPrior().getParameters()).getArray());

    comparator.append(((DenseVector) this
        .getEdgeMotionTransProbPrior().getParameters()).getArray(),
        ((DenseVector) o.getEdgeMotionTransProbPrior()
            .getParameters()).getArray());
    comparator.append(((DenseVector) this
        .getFreeMotionTransProbPrior().getParameters()).getArray(),
        ((DenseVector) o.getFreeMotionTransProbPrior()
            .getParameters()).getArray());

    return comparator.toComparison();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final OnOffEdgeTransDirMulti other = (OnOffEdgeTransDirMulti) obj;
    if (getEdgeMotionTransPrior() == null) {
      if (other.getEdgeMotionTransPrior() != null) {
        return false;
      }
    } else if (!StatisticsUtil.vectorEquals(getEdgeMotionTransPrior()
        .getParameters(), other.getEdgeMotionTransPrior()
        .getParameters())) {
      return false;
    }
    if (getEdgeMotionTransProbPrior() == null) {
      if (other.getEdgeMotionTransProbPrior() != null) {
        return false;
      }
    } else if (!StatisticsUtil.vectorEquals(
        getEdgeMotionTransProbPrior().getParameters(), other
            .getEdgeMotionTransProbPrior().getParameters())) {
      return false;
    }
    if (getFreeMotionTransPrior() == null) {
      if (other.getFreeMotionTransPrior() != null) {
        return false;
      }
    } else if (!StatisticsUtil.vectorEquals(getFreeMotionTransPrior()
        .getParameters(), other.getFreeMotionTransPrior()
        .getParameters())) {
      return false;
    }
    if (getFreeMotionTransProbPrior() == null) {
      if (other.getFreeMotionTransProbPrior() != null) {
        return false;
      }
    } else if (!StatisticsUtil.vectorEquals(
        getFreeMotionTransProbPrior().getParameters(), other
            .getFreeMotionTransProbPrior().getParameters())) {
      return false;
    }
    return true;
  }

  public double evaluate(InferredEdge from, InferredEdge to) {
    if (from.isEmptyEdge()) {
      return getFreeMotionTransPrior().getProbabilityFunction()
          .evaluate(getTransitionType(from, to));
    } else {
      return getEdgeMotionTransPrior().getProbabilityFunction()
          .evaluate(getTransitionType(from, to));
    }
  }

  public MultinomialDistribution getEdgeMotionTransPrior() {
    return edgeMotionTransPrior;
  }

  public DirichletDistribution getEdgeMotionTransProbPrior() {
    return edgeMotionTransProbPrior;
  }

  public MultinomialDistribution getFreeMotionTransPrior() {
    return freeMotionTransPrior;
  }

  public DirichletDistribution getFreeMotionTransProbPrior() {
    return freeMotionTransProbPrior;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result =
        prime
            * result
            + ((getEdgeMotionTransPrior() == null) ? 0
                : StatisticsUtil
                    .hashCodeVector(getEdgeMotionTransPrior()
                        .getParameters()));
    result =
        prime
            * result
            + ((getEdgeMotionTransProbPrior() == null) ? 0
                : StatisticsUtil
                    .hashCodeVector(getEdgeMotionTransProbPrior()
                        .getParameters()));
    result =
        prime
            * result
            + ((getFreeMotionTransPrior() == null) ? 0
                : StatisticsUtil
                    .hashCodeVector(getFreeMotionTransPrior()
                        .getParameters()));
    result =
        prime
            * result
            + ((getFreeMotionTransProbPrior() == null) ? 0
                : StatisticsUtil
                    .hashCodeVector(getFreeMotionTransProbPrior()
                        .getParameters()));
    return result;
  }

  public double logEvaluate(InferredEdge from, InferredEdge to) {
    if (from == null) {
      /*
       * This corresponds to the initialization/prior
       * XXX FIXME: use the to -> to transition
       */
      if (to.isEmptyEdge()) {
        return getFreeMotionTransPrior().getProbabilityFunction()
            .logEvaluate(getTransitionType(to, to));
      } else {
        return getEdgeMotionTransPrior().getProbabilityFunction()
            .logEvaluate(getTransitionType(to, to));
      }

    } else {
      if (from.isEmptyEdge()) {
        return getFreeMotionTransPrior().getProbabilityFunction()
            .logEvaluate(getTransitionType(from, to));
      } else {
        return getEdgeMotionTransPrior().getProbabilityFunction()
            .logEvaluate(getTransitionType(from, to));
      }
    }
  }

  public double predictiveLogLikelihood(InferredEdge from,
    InferredEdge to) {
    final Vector state = getTransitionType(from, to);
    if (from.isEmptyEdge()) {
      final MultivariatePolyaDistribution predDist =
          freeMotionTransEstimator
              .createPredictiveDistribution(getFreeMotionTransProbPrior());
      return predDist.getProbabilityFunction().logEvaluate(state);
    } else {
      final MultivariatePolyaDistribution predDist =
          edgeMotionTransEstimator
              .createPredictiveDistribution(getEdgeMotionTransProbPrior());
      return predDist.getProbabilityFunction().logEvaluate(state);
    }
  }

  public InferredEdge sample(Random rng,
    List<InferredEdge> transferEdges, InferredEdge currentEdge) {

    Preconditions.checkNotNull(currentEdge);
    Preconditions.checkNotNull(transferEdges);

    if (currentEdge == InferredEdge.getEmptyEdge()) {
      /*
       * We're currently in free-motion. If there are transfer edges, then
       * sample from those.
       */
      if (transferEdges.isEmpty()) {
        return InferredEdge.getEmptyEdge();
      } else {
        final Vector sample =
            this.getFreeMotionTransPrior().sample(rng);

        if (sample.equals(stateOffToOn)) {
          List<InferredEdge> support = Lists.newArrayList(transferEdges); 
          support.remove(InferredEdge.getEmptyEdge());
          return support.get(rng.nextInt(support.size()));
        } else {
          return InferredEdge.getEmptyEdge();
        }
      }
    } else {
      /*
       * We're on an edge, so sample whether we go off-road, or transfer/stay
       * on.
       * If the empty edge is contained in the support (transferEdges)
       * then we sample for that.
       */
      final Vector sample = transferEdges.contains(InferredEdge.getEmptyEdge()) ?
          this.getEdgeMotionTransPrior().sample(rng) : stateOnToOn;

      if (sample.equals(stateOnToOff) || transferEdges.isEmpty()) {
        return InferredEdge.getEmptyEdge();
      } else {
        final List<InferredEdge> support = Lists.newArrayList(transferEdges); 
        support.remove(InferredEdge.getEmptyEdge());
        return support.get(rng.nextInt(support.size()));
      }

    }

  }

  public void setEdgeMotionTransPrior(
    MultinomialDistribution edgeMotionTransPrior) {
    this.edgeMotionTransPrior = edgeMotionTransPrior;
  }

  public void setEdgeMotionTransProbPrior(
    DirichletDistribution edgeMotionTransProbPrior) {
    this.edgeMotionTransProbPrior = edgeMotionTransProbPrior;
  }

  public void setFreeMotionTransPrior(
    MultinomialDistribution freeMotionTransPrior) {
    this.freeMotionTransPrior = freeMotionTransPrior;
  }

  public void setFreeMotionTransProbPrior(
    DirichletDistribution freeMotionTransProbPrior) {
    this.freeMotionTransProbPrior = freeMotionTransProbPrior;
  }

  @Override
  public String toString() {
    return "EdgeTransitionDistributions [freeMotionTransPrior="
        + getFreeMotionTransPrior().getParameters()
        + ", edgeMotionTransPrior="
        + getEdgeMotionTransPrior().getParameters() + "]";
  }

  public void update(InferredEdge from, InferredEdge to) {
    final Vector transType = getTransitionType(from, to);
    if (from.isEmptyEdge()) {
      freeMotionTransEstimator.update(getFreeMotionTransProbPrior(),
          transType);
    } else {
      edgeMotionTransEstimator.update(getEdgeMotionTransProbPrior(),
          transType);
    }
  }

  public static Vector getStateOffToOff() {
    return stateOffToOff;
  }

  public static Vector getStateOffToOn() {
    return stateOffToOn;
  }

  public static Vector getStateOnToOff() {
    return stateOnToOff;
  }

  public static Vector getStateOnToOn() {
    return stateOnToOn;
  }

  public static Vector getTransitionType(InferredEdge from,
    InferredEdge to) {
    if (from.isEmptyEdge()) {
      if (to.isEmptyEdge()) {
        return stateOffToOff;
      } else {
        return stateOffToOn;
      }
    } else {
      if (!to.isEmptyEdge()) {
        return stateOnToOn;
      } else {
        return stateOnToOff;
      }
    }
  }

}
