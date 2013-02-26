package org.opentrackingtools.updater;

import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.ParticleFilter;
import gov.sandia.cognition.statistics.distribution.DefaultDataDistribution;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nonnull;

import org.opentrackingtools.VehicleStateInitialParameters;
import org.opentrackingtools.distributions.DefaultCountedDataDistribution;
import org.opentrackingtools.distributions.OnOffEdgeTransDistribution;
import org.opentrackingtools.edges.InferredEdge;
import org.opentrackingtools.estimators.AbstractRoadTrackingFilter;
import org.opentrackingtools.graph.InferenceGraph;
import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.VehicleState;
import org.opentrackingtools.paths.InferredPath;
import org.opentrackingtools.paths.PathEdge;
import org.opentrackingtools.paths.PathStateBelief;
import org.opentrackingtools.paths.impl.InferredPathPrediction;

import com.google.common.collect.Sets;
import com.vividsolutions.jts.linearref.LengthIndexedLine;

public abstract class VehicleTrackingPLFilterUpdater implements ParticleFilter.Updater<GpsObservation, VehicleState> {

  private static final long serialVersionUID = 7567157323292175525L;

  protected GpsObservation initialObservation;

  protected InferenceGraph inferenceGraph;

  protected VehicleStateInitialParameters parameters;

  protected Random random;

  public long seed;

  protected final AbstractRoadTrackingFilter roadFilterGenerator;

  public VehicleTrackingPLFilterUpdater(GpsObservation obs, InferenceGraph inferencedGraph,
    VehicleStateInitialParameters parameters, Random rng) throws ClassNotFoundException, SecurityException,
      NoSuchMethodException, IllegalArgumentException, InstantiationException, IllegalAccessException,
      InvocationTargetException {

    Class<?> filterType = Class.forName(parameters.getRoadFilterTypeName());

    Constructor<?> ctor =
        filterType.getConstructor(GpsObservation.class, InferenceGraph.class, VehicleStateInitialParameters.class,
            Random.class);

    roadFilterGenerator = (AbstractRoadTrackingFilter) ctor.newInstance(obs, inferencedGraph, parameters, rng);

    this.initialObservation = obs;
    this.inferenceGraph = inferencedGraph;
    if (rng == null)
      this.random = new Random();
    else
      this.random = rng;
    this.parameters = parameters;

  }

  @Override
  public VehicleTrackingPLFilterUpdater clone() {
    try {
      final VehicleTrackingPLFilterUpdater clone = (VehicleTrackingPLFilterUpdater) super.clone();
      clone.seed = seed;
      clone.inferenceGraph = inferenceGraph;
      clone.initialObservation = initialObservation;
      clone.parameters = parameters;
      clone.random = random;
      return clone;
    } catch (final CloneNotSupportedException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Nonnull
  @Override
  public DataDistribution<VehicleState> createInitialParticles(int numParticles) {

    final DataDistribution<VehicleState> retDist = new DefaultCountedDataDistribution<VehicleState>(true);

    for (int i = 0; i < numParticles; i++) {
      final AbstractRoadTrackingFilter tmpTrackingFilter = this.createRoadTrackingFilter();
      final MultivariateGaussian groundInitialBelief = tmpTrackingFilter.getGroundFilter().createInitialLearnedObject();
      final Vector xyPoint = initialObservation.getProjectedPoint();
      groundInitialBelief.setMean(VectorFactory.getDefault().copyArray(
          new double[] { xyPoint.getElement(0), 0d, xyPoint.getElement(1), 0d }));
      final Collection<InferredEdge> initialEdges =
          inferenceGraph.getNearbyEdges(groundInitialBelief, tmpTrackingFilter);

      final DataDistribution<VehicleState> initialDist = new DefaultDataDistribution<VehicleState>(numParticles);

      final Set<InferredPathPrediction> evaluatedPaths = Sets.newHashSet();
      if (!initialEdges.isEmpty()) {
        for (final InferredEdge edge : initialEdges) {
          final PathEdge pathEdge = this.inferenceGraph.getPathEdge(edge, 0d, false);
          final InferredPath path = this.inferenceGraph.getInferredPath(pathEdge);
          evaluatedPaths.add(new InferredPathPrediction(path, null, null, null, Double.NEGATIVE_INFINITY));

          /*
           * TODO this code should be contained in the filter,
           * or somewhere other than here, no?
           */
          final AbstractRoadTrackingFilter trackingFilter = this.createRoadTrackingFilter();

          final MultivariateGaussian initialBelief = trackingFilter.getRoadFilter().createInitialLearnedObject();

          final double lengthLocation =
              new LengthIndexedLine(edge.getGeometry()).project(initialObservation.getObsProjected());

          final Vector stateSmpl = trackingFilter.sampleStateTransDist(initialBelief.getMean(), this.random);
          initialBelief.setMean(stateSmpl);
          initialBelief.getMean().setElement(0, lengthLocation);

          final PathStateBelief pathStateBelief = path.getStateBeliefOnPath(initialBelief);

          final OnOffEdgeTransDistribution edgeTransDist =
              new OnOffEdgeTransDistribution(inferenceGraph, parameters.getOnTransitionProbs(),
                  parameters.getOffTransitionProbs(), this.random);

          final VehicleState state =
              this.inferenceGraph.createVehicleState(initialObservation, trackingFilter, pathStateBelief,
                  edgeTransDist, null);

          final double lik = computeLogLikelihood(state, initialObservation);

          initialDist.increment(state, lik);
        }
      }

      /*
       * Free-motion
       */
      final AbstractRoadTrackingFilter trackingFilter = this.createRoadTrackingFilter();

      final OnOffEdgeTransDistribution edgeTransDist =
          new OnOffEdgeTransDistribution(inferenceGraph, parameters.getOnTransitionProbs(),
              parameters.getOffTransitionProbs());

      final MultivariateGaussian initialBelief = groundInitialBelief.clone();

      final Vector stateSmpl = trackingFilter.sampleStateTransDist(initialBelief.getMean(), this.random);

      initialBelief.setMean(stateSmpl);

      final PathStateBelief pathStateBelief = this.inferenceGraph.getNullPath().getStateBeliefOnPath(initialBelief);

      /*
       * Sample an initial prior for the transition probabilities
       */
      final Vector edgeDriorParams =
          OnOffEdgeTransDistribution.checkedSample(edgeTransDist.getEdgeMotionTransProbPrior(), this.random);
      final Vector freeDriorParams =
          OnOffEdgeTransDistribution.checkedSample(edgeTransDist.getFreeMotionTransProbPrior(), this.random);

      edgeTransDist.getEdgeMotionTransPrior().setParameters(edgeDriorParams);
      edgeTransDist.getFreeMotionTransPrior().setParameters(freeDriorParams);

      final VehicleState state =
          this.inferenceGraph.createVehicleState(initialObservation, trackingFilter, pathStateBelief, edgeTransDist,
              null);

      final double lik = computeLogLikelihood(state, initialObservation);

      initialDist.increment(state, lik);

      retDist.increment(initialDist.sample(this.random));
    }

    return retDist;
  }

  @Override
  public double computeLogLikelihood(VehicleState particle, GpsObservation observation) {

    double logLikelihood = 0d;

    /*
     * Movement.
     */
    logLikelihood += particle.getBelief().logLikelihood(observation.getProjectedPoint(), particle.getMovementFilter());

    return logLikelihood;
  }

  @Override
  public VehicleState update(VehicleState previousParameter) {
    throw new RuntimeException("Not implemented");
  }

  @Nonnull
  protected AbstractRoadTrackingFilter createRoadTrackingFilter() {
    return roadFilterGenerator.clone();
  }

  @Nonnull
  public InferenceGraph getInferredGraph() {
    return inferenceGraph;
  }

  @Nonnull
  public GpsObservation getInitialObservation() {
    return initialObservation;
  }

  @Nonnull
  public VehicleStateInitialParameters getParameters() {
    return parameters;
  }

  public Random getRandom() {
    return random;
  }

  public long getSeed() {
    return seed;
  }

  public void setRandom(Random random) {
    this.random = random;
  }

  public void setSeed(long seed) {
    this.seed = seed;
  }

}