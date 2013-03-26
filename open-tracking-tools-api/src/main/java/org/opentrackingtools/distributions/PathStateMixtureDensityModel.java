package org.opentrackingtools.distributions;

import gov.sandia.cognition.math.LogMath;
import gov.sandia.cognition.math.RingAccumulator;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.ClosedFormComputableDistribution;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.Distribution;
import gov.sandia.cognition.statistics.ProbabilityDensityFunction;
import gov.sandia.cognition.statistics.ProbabilityFunction;
import gov.sandia.cognition.statistics.distribution.DefaultDataDistribution;
import gov.sandia.cognition.statistics.distribution.LinearMixtureModel;
import gov.sandia.cognition.util.ObjectUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.opentrackingtools.paths.Path;
import org.opentrackingtools.paths.PathEdge;
import org.opentrackingtools.paths.PathState;

import com.beust.jcommander.internal.Maps;

/**
 * Copy of MultivariateMixtureDensityModel for path states.<br>
 * Note: this takes log-scale weights! 
 */
public class PathStateMixtureDensityModel
    extends LinearMixtureModel<PathState, PathStateDistribution> implements
    ClosedFormComputableDistribution<PathState> {

  /**
   * PDF of the MultivariateMixtureDensityModel
   * 
   * @param <PathStateDistribution>
   *          Type of Distribution in the mixture
   */
  public static class PDF
      extends PathStateMixtureDensityModel
      implements ProbabilityDensityFunction<PathState> {

    private static final long serialVersionUID = -715039776358524176L;

    public PDF(Collection<? extends PathStateDistribution> distributions,
      double[] priorWeights) {
      super(distributions, priorWeights);
    }

    public PDF(
      PathStateMixtureDensityModel other) {
      super(other);
    }

    public double[] computeRandomVariableLikelihoods(PathState input) {

      final int K = this.getDistributionCount();
      final double[] likelihoods = new double[K];
      for (int k = 0; k < K; k++) {
        final ProbabilityFunction<PathState> pdf =
            this.getDistributions().get(k).getProbabilityFunction();
        likelihoods[k] = pdf.evaluate(input);
      }

      return likelihoods;
    }

    /**
     * Computes the probability distribution that the input was generated by the
     * underlying distributions
     * 
     * @param input
     *          Input to consider
     * @return probability distribution that the input was generated by the
     *         underlying distributions
     */
    public double[]
        computeRandomVariableProbabilities(PathState input) {
      final int K = this.getDistributionCount();
      final double[] likelihoods =
          this.computeRandomVariableLikelihoods(input);
      double sum = 0.0;
      for (int k = 0; k < K; k++) {
        sum += likelihoods[k];
      }
      if (sum <= 0.0) {
        Arrays.fill(likelihoods, 1.0 / K);
      }

      sum = 0.0;
      for (int k = 0; k < K; k++) {
        likelihoods[k] *= this.priorWeights[k];
        sum += likelihoods[k];
      }
      if (sum <= 0.0) {
        Arrays.fill(likelihoods, 1.0 / K);
        sum = 1.0;
      }
      for (int k = 0; k < K; k++) {
        likelihoods[k] /= sum;
      }

      return likelihoods;

    }

    @Override
    public Double evaluate(PathState input) {
      double sum = 0.0;
      final int K = this.getDistributionCount();
      for (int k = 0; k < K; k++) {
        final ProbabilityFunction<PathState> pdf =
            this.getDistributions().get(k).getProbabilityFunction();
        sum += pdf.evaluate(input) * this.priorWeights[k];
      }

      return sum / this.getPriorWeightSum();
    }

    public int getMostLikelyRandomVariable(PathState input) {

      final double[] probabilities =
          this.computeRandomVariableProbabilities(input);
      int bestIndex = 0;
      double bestProbability = probabilities[0];
      for (int i = 1; i < probabilities.length; i++) {
        final double prob = probabilities[i];
        if (bestProbability < prob) {
          bestProbability = prob;
          bestIndex = i;
        }
      }

      return bestIndex;

    }

    @Override
    public PathStateMixtureDensityModel.PDF
        getProbabilityFunction() {
      return this;
    }

    @Override
    public double logEvaluate(PathState input) {
      return Math.log(this.evaluate(input));
    }

  }

  private static final long serialVersionUID = 5143671580377145903L;

  public PathStateMixtureDensityModel(
    Collection<? extends PathStateDistribution> distributions,
    double[] priorWeights) {
    super(distributions);
    super.setPriorWeights(priorWeights);
  }

  @Override
  public double getPriorWeightSum() {
    if (priorWeights.length <= 0) 
      return Double.NEGATIVE_INFINITY;
    if (priorWeights.length == 1)
      return priorWeights[0];
    double logSum = priorWeights[0];
    for (int i = 1; i < priorWeights.length; i++) {
      logSum = LogMath.add(logSum, priorWeights[i]);
    }
    return logSum;
  }

  public PathStateMixtureDensityModel(
    PathStateMixtureDensityModel other) {
    this(ObjectUtil.cloneSmartElementsAsArrayList(other
        .getDistributions()), ObjectUtil.deepCopy(other
        .getPriorWeights()));
  }

  @Override
  public PathStateMixtureDensityModel clone() {
    final PathStateMixtureDensityModel clone =
        (PathStateMixtureDensityModel) super
            .clone();
    return clone;
  }

  @Override
  public void convertFromVector(Vector parameters) {
    parameters
        .assertDimensionalityEquals(this.getDistributionCount());
    for (int k = 0; k < parameters.getDimensionality(); k++) {
      this.priorWeights[k] = parameters.getElement(k);
    }
  }

  @Override
  public Vector convertToVector() {
    return VectorFactory.getDefault().copyArray(
        this.getPriorWeights());
  }

  /**
   * Returns the first highest-probability path edge and its mean location, as a
   * path state, naturally.
   */
  @Override
  public PathState getMean() {

    final Map<PathEdge, RingAccumulator<Vector>> edgeToMean = Maps.newHashMap();
    final Map<PathEdge, DataDistribution<Path>> edgeToPaths = Maps.newHashMap();
    final int K = this.getDistributionCount();
    final DefaultDataDistribution<PathEdge> edgeDist =
        new DefaultDataDistribution<PathEdge>();
    for (int k = 0; k < K; k++) {
      final double priorWeight = this.getPriorWeights()[k];
      final PathStateDistribution dist = this.getDistributions().get(k);
      PathEdge edge = dist.getMean().getEdge();
      edgeDist.increment(edge, priorWeight);
      
      Path path = dist.getMean().getPath();
      DataDistribution<Path> pathDist = edgeToPaths.get(path);
      if (pathDist == null) {
        pathDist = new DefaultDataDistribution<Path>();
        pathDist.increment(path, priorWeight);
      }

      RingAccumulator<Vector> mean =
          edgeToMean.get(edge);

      if (mean == null) {
        mean = new RingAccumulator<Vector>();
        edgeToMean.put(edge, mean);
      }
      mean.accumulate(dist.getMean().scale(priorWeight));
    }

    final PathEdge bestEdge = edgeDist.getMaxValueKey();
    final Path bestPath = edgeToPaths.get(bestEdge).getMaxValueKey();
    final RingAccumulator<Vector> mean = edgeToMean.get(bestEdge);
    return new PathState(bestPath, mean.getMean());

  }

  @Override
  public PathStateMixtureDensityModel.PDF
      getProbabilityFunction() {
    return new PathStateMixtureDensityModel.PDF(
        this);
  }

}
