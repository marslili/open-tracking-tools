package org.opentrackingtools;

import gov.sandia.cognition.math.MutableDouble;
import gov.sandia.cognition.math.RingAccumulator;
import gov.sandia.cognition.math.matrix.Matrix;
import gov.sandia.cognition.math.matrix.MatrixFactory;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorEntry;
import gov.sandia.cognition.math.matrix.VectorFactory;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.bayesian.ParticleFilter;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian;
import gov.sandia.cognition.statistics.distribution.MultivariateGaussian.SufficientStatistic;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.geotools.factory.FactoryRegistryException;
import org.geotools.referencing.operation.projection.ProjectionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.operation.NoninvertibleTransformException;
import org.opengis.referencing.operation.TransformException;
import org.opentrackingtools.distributions.OnOffEdgeTransDistribution;
import org.opentrackingtools.estimators.MotionStateEstimatorPredictor;
import org.opentrackingtools.graph.GenericJTSGraph;
import org.opentrackingtools.graph.InferenceGraph;
import org.opentrackingtools.model.GpsObservation;
import org.opentrackingtools.model.VehicleStateDistribution;
import org.opentrackingtools.paths.PathState;
import org.opentrackingtools.util.Simulation;
import org.opentrackingtools.util.TestUtils;
import org.opentrackingtools.util.Simulation.SimulationParameters;
import org.opentrackingtools.util.TrueObservation;
import org.testng.AssertJUnit;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.internal.junit.ArrayAsserts;

import com.google.common.base.Stopwatch;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;

public class RoadTrackingFilterGraphTest {

  private static final double[] fourZeros =
      new double[] { 0, 0, 0, 0 };

  private static final double[] oneZero = new double[] { 0 };
  private static final double[] sixteenZeros = VectorFactory
      .getDefault().createVector(16).toArray();
  private static final double[] twoZeros = new double[] { 0, 0 };

  @DataProvider
  private static final Object[][] initialStateData() {
    return new Object[][] { {
        /*
         * Road only
         */
        new VehicleStateInitialParameters(
            null,
            VectorFactory.getDefault()
            .createVector2D(70d, 70d), 20, VectorFactory.getDefault()
            .createVector1D(6.25e-4), 20, VectorFactory.getDefault()
            .createVector2D(6.25e-4, 6.25e-4), 20, VectorFactory
            .getDefault().createVector2D(1d, Double.MAX_VALUE),
            VectorFactory.getDefault().createVector2D(
                Double.MAX_VALUE, 1d), 25, 30, 2159585l),
        new VehicleStateInitialParameters(
            null,
            VectorFactory.getDefault()
            .createVector2D(70d, 70d), 20, VectorFactory.getDefault()
            .createVector1D(6.25e-4), 20, VectorFactory.getDefault()
            .createVector2D(6.25e-4, 6.25e-4), 20, VectorFactory
            .getDefault().createVector2D(1d, Double.MAX_VALUE),
            VectorFactory.getDefault().createVector2D(
                Double.MAX_VALUE, 1d), 25, 30, 2159585l),
        Boolean.FALSE, 36000 }
    //        ,{
    //          /*
    //           * Mixed 
    //           */
    //        new VehicleStateInitialParameters(VectorFactory
    //            .getDefault().createVector2D(70d, 70d), 20,
    //            VectorFactory.getDefault().createVector1D(
    //                6.25e-4), 20, VectorFactory.getDefault()
    //                .createVector2D(6.25e-4, 6.25e-4), 20,
    //            VectorFactory.getDefault().createVector2D(0.5d,
    //                0.5d), VectorFactory.getDefault()
    //                .createVector2D(0.5d, 0.5d),
    //            VehicleTrackingPLFilter.class.getName(), 
    //            ErrorEstimatingRoadTrackingFilter.class.getName(), 
    //            25, 30, 2159585l),
    //        new VehicleStateInitialParameters(VectorFactory
    //            .getDefault().createVector2D(70d, 70d), 20,
    //            VectorFactory.getDefault().createVector1D(
    //                6.25e-4), 20, VectorFactory.getDefault()
    //                .createVector2D(6.25e-4, 6.25e-4), 20,
    //            VectorFactory.getDefault().createVector2D(70d,
    //                30d), VectorFactory.getDefault()
    //                .createVector2D(30d, 70d),
    //            VehicleTrackingPLFilter.class.getName(), 
    //            ErrorEstimatingRoadTrackingFilter.class.getName(), 
    //            25, 30, 2159585l),
    //        Boolean.FALSE,
    //        36000
    //        }
    };
  }

  private Matrix avgTransform;
  private InferenceGraph graph;
  final Logger log = Logger
      .getLogger(RoadTrackingFilterGraphTest.class);
  private Simulation sim;

  private Coordinate startCoord;

  private VehicleStateDistribution<GpsObservation> resetState(
    VehicleStateDistribution<GpsObservation> vehicleState) {
    return this.sim.computeInitialState();
  }

//  @Test(dataProvider = "initialStateData")
  public void runSimulation(
    VehicleStateInitialParameters simInitialParams,
    VehicleStateInitialParameters filterInitialParams,
    boolean generalizeMoveDiff, long duration)
      throws NoninvertibleTransformException, TransformException,
      ClassNotFoundException, SecurityException,
      NoSuchMethodException, IllegalArgumentException,
      InstantiationException, IllegalAccessException,
      InvocationTargetException {

    final SimulationParameters simParams =
        new SimulationParameters(this.startCoord, new Date(0l),
            duration, simInitialParams.getInitialObsFreq(), false,
            false, simInitialParams);

    this.sim =
        new Simulation("test-sim", this.graph, simParams,
            simInitialParams);

    long time = this.sim.getSimParameters().getStartTime().getTime();

    VehicleStateDistribution<GpsObservation> trueVehicleState =
        this.sim.computeInitialState();

    final ParticleFilter<GpsObservation, VehicleStateDistribution<GpsObservation>> filter =
        new VehicleStatePLFilter<GpsObservation>(
            trueVehicleState.getObservation(), this.graph,
            filterInitialParams, null, null);

    if (filterInitialParams.getSeed() != 0) {
      new Random(filterInitialParams.getSeed());
    } else {
      new Random();
    }

    final DataDistribution<VehicleStateDistribution<GpsObservation>> vehicleStateDist =
        filter.getUpdater().createInitialParticles(
            filterInitialParams.getNumParticles());

    final MultivariateGaussian.SufficientStatistic obsErrorSS =
        new MultivariateGaussian.SufficientStatistic();
    final MultivariateGaussian.SufficientStatistic stateErrorSS =
        new MultivariateGaussian.SufficientStatistic();
    final MultivariateGaussian.SufficientStatistic obsCovErrorSS =
        new MultivariateGaussian.SufficientStatistic();
    final MultivariateGaussian.SufficientStatistic onRoadCovErrorSS =
        new MultivariateGaussian.SufficientStatistic();
    final MultivariateGaussian.SufficientStatistic offRoadCovErrorSS =
        new MultivariateGaussian.SufficientStatistic();
    final MultivariateGaussian.SufficientStatistic transitionsSS =
        new MultivariateGaussian.SufficientStatistic();

    final RingAccumulator<MutableDouble> averager =
        new RingAccumulator<MutableDouble>();
    final Stopwatch watch = new Stopwatch();

    final long approxRuns =
        this.sim.getSimParameters().getDuration()
            / Math.round(this.sim.getSimParameters().getFrequency());

    this.updateAndCheckStats(vehicleStateDist, trueVehicleState,
        obsErrorSS, stateErrorSS, obsCovErrorSS, onRoadCovErrorSS,
        offRoadCovErrorSS, transitionsSS, generalizeMoveDiff);

    do {
      try {
        trueVehicleState = this.sim.stepSimulation(trueVehicleState);

        for (final VectorEntry entry : trueVehicleState
            .getMotionStateParam().getValue()) {
          AssertJUnit.assertTrue(entry.getValue() >= 0d);
        }

        watch.reset();
        watch.start();

        filter.update(vehicleStateDist, new TrueObservation(
            trueVehicleState.getObservation(), trueVehicleState));

        watch.stop();
        averager.accumulate(new MutableDouble(watch.elapsedMillis()));

        if (averager.getCount() > 1 && averager.getCount() % 20 == 0) {
          this.log.info("avg. records per sec = " + 1000d
              / averager.getMean().value);
        }

        this.updateAndCheckStats(vehicleStateDist, trueVehicleState,
            obsErrorSS, stateErrorSS, obsCovErrorSS,
            onRoadCovErrorSS, offRoadCovErrorSS, transitionsSS,
            generalizeMoveDiff);

        time =
            trueVehicleState.getObservation().getTimestamp()
                .getTime();
      } catch (final ProjectionException ex) {
        trueVehicleState = this.resetState(trueVehicleState);
        //        if (vehicleState.getParentState() != null)
        //          vehicleState.setParentState(resetState(vehicleState.getParentState()));
        this.log
            .warn("Outside of projection!  Flipped state velocities");
      }

    } while (time < this.sim.getSimParameters().getEndTime()
        .getTime());

    AssertJUnit
        .assertTrue(transitionsSS.getCount() > 0.95d * approxRuns);
  }

  @BeforeTest
  public void setUp() throws NoSuchAuthorityCodeException,
      FactoryRegistryException, FactoryException, IOException {

    this.log.setLevel(Level.DEBUG);

    this.startCoord = new Coordinate(40.7549, -73.97749);

    final List<LineString> edges =
        TestUtils.createGridGraph(this.startCoord);
    this.graph = new GenericJTSGraph(edges, true);

    this.avgTransform =
        MatrixFactory
            .getDefault()
            .copyArray(
                new double[][] { { 1, 0, 1, 0 }, { 0, 1, 0, 1 } })
            .scale(1d / 2d);
  }

  private void updateAndCheckStats(
    DataDistribution<VehicleStateDistribution<GpsObservation>> vehicleStateDist,
    VehicleStateDistribution<GpsObservation> trueVehicleState,
    SufficientStatistic obsErrorSS, SufficientStatistic stateErrorSS,
    SufficientStatistic obsCovErrorSS,
    SufficientStatistic onRoadCovErrorSS,
    SufficientStatistic offRoadCovErrorSS,
    SufficientStatistic transitionsSS, boolean generalizeMoveDiff) {

    final SufficientStatistic stateMeanStat =
        new MultivariateGaussian.SufficientStatistic();
    final SufficientStatistic obsCovMeanStat =
        new MultivariateGaussian.SufficientStatistic();
    final SufficientStatistic onRoadCovStat =
        new MultivariateGaussian.SufficientStatistic();
    final SufficientStatistic offRoadCovStat =
        new MultivariateGaussian.SufficientStatistic();
    final SufficientStatistic transitionStat =
        new MultivariateGaussian.SufficientStatistic();

    int truePathsFound = 0;
    int numOnTrueEdge = 0;
    final boolean hasPriorOnVariances = false;

    for (final VehicleStateDistribution<GpsObservation> state : vehicleStateDist
        .getDomain()) {

      AssertJUnit
          .assertEquals(
              new TrueObservation(trueVehicleState.getObservation(),
                  null), state.getObservation());

      final PathState pathState =
          state.getPathStateParam().getValue();
      final PathState truePathState =
          state.getPathStateParam().getValue();
      if (pathState.isOnRoad()
          && truePathState.isOnRoad()
          && pathState.getPath().getGeometry()
              .covers(truePathState.getPath().getGeometry())) {
        truePathsFound++;
      }

      if (pathState.getEdge().getInferenceGraphEdge()
          .equals(truePathState.getEdge().getInferenceGraphEdge())) {
        numOnTrueEdge++;
      }

      state.getPathStateParam().getParameterPrior();
      stateMeanStat.update(pathState.getGroundState());

      obsCovMeanStat.update(state.getObservationCovarianceParam()
          .getValue().convertToVector());
      onRoadCovStat.update(state.getOnRoadModelCovarianceParam()
          .getValue().convertToVector());
      offRoadCovStat.update(state.getOffRoadModelCovarianceParam()
          .getValue().convertToVector());

      final VehicleStateDistribution<GpsObservation> parentState =
          state.getParentState();
      if (parentState != null) {

        final PathState parentPathState =
            parentState.getPathStateParam().getValue();
        Vector transType =
            OnOffEdgeTransDistribution.getTransitionType(
                parentPathState.getEdge().getInferenceGraphEdge(),
                pathState.getEdge().getInferenceGraphEdge());

        if (parentPathState.isOnRoad()) {
          transType =
              transType.stack(MotionStateEstimatorPredictor.zeros2D);
        } else {
          transType =
              MotionStateEstimatorPredictor.zeros2D.stack(transType);
        }

        transitionStat.update(transType);
      }

    }

    this.log.debug("truePathsFound=" + truePathsFound);
    this.log.debug("numOnTrueEdges=" + numOnTrueEdge);

    final Vector obsError =
        trueVehicleState
            .getObservation()
            .getProjectedPoint()
            .minus(
                MotionStateEstimatorPredictor.getOg().times(
                    stateMeanStat.getMean()));
    final PathState truePathState =
        trueVehicleState.getPathStateParam().getValue();
    final Vector stateError =
        truePathState.getGroundState().minus(stateMeanStat.getMean());

    final Vector obsCovError;
    final Vector onRoadCovError;
    final Vector offRoadCovError;
    obsCovError =
        obsCovMeanStat.getMean().minus(
            trueVehicleState.getObservationCovarianceParam()
                .getValue().convertToVector());
    onRoadCovError =
        onRoadCovStat.getMean().minus(
            trueVehicleState.getOnRoadModelCovarianceParam()
                .getValue().convertToVector());
    offRoadCovError =
        offRoadCovStat.getMean().minus(
            trueVehicleState.getOffRoadModelCovarianceParam()
                .getValue().convertToVector());

    this.log.debug("obsError=" + obsError);
    this.log.debug("stateError=" + stateError);

    this.log.debug("obsCovMean=" + obsCovMeanStat.getMean());
    this.log.debug("onRoadCovMean=" + onRoadCovStat.getMean());
    this.log.debug("offRoadCovMean=" + offRoadCovStat.getMean());

    if (transitionStat.getMean() != null) {
      final Vector trueTransProbs =
          trueVehicleState
              .getEdgeTransitionParam()
              .getParameterPrior()
              .getEdgeMotionTransProbPrior()
              .getMean()
              .stack(
                  trueVehicleState.getEdgeTransitionParam()
                      .getParameterPrior()
                      .getFreeMotionTransProbPrior().getMean());

      transitionsSS.update(transitionStat.getMean().minus(
          trueTransProbs));
      //     log.debug("transitionMean=" + transitionStat.getMean());
    }

    obsErrorSS.update(obsError);
    stateErrorSS.update(stateError);
    obsCovErrorSS.update(obsCovError);
    onRoadCovErrorSS.update(onRoadCovError);
    offRoadCovErrorSS.update(offRoadCovError);

    if (!hasPriorOnVariances) {
      this.log.debug("obsErrorSS=" + obsErrorSS.getMean());
      this.log.debug("stateErrorSS=" + stateErrorSS.getMean());
      //      log.debug("obsCovErrorSS=" + obsCovErrorSS.getMean());
      //      log.debug("onRoadCovErrorSS=" + onRoadCovErrorSS.getMean());
      //      log.debug("offRoadCovErrorSS=" + offRoadCovErrorSS.getMean());
      //      log.debug("transitionErrorSS=" + transitionsSS.getMean());
    }

    final long approxRuns =
        this.sim.getSimParameters().getDuration()
            / Math.round(this.sim.getSimParameters().getFrequency());
    if (obsErrorSS.getCount() > Math.min(approxRuns / 16, 155)) {

      ArrayAsserts.assertArrayEquals(obsErrorSS.getMean()
          .getDimensionality() == 4
          ? RoadTrackingFilterGraphTest.fourZeros
          : RoadTrackingFilterGraphTest.twoZeros, obsErrorSS
          .getMean().toArray(), 3d * Math
          .sqrt(trueVehicleState.getObservationCovarianceParam()
              .getValue().normFrobenius()));

      ArrayAsserts.assertArrayEquals(stateErrorSS.getMean()
          .getDimensionality() == 4
          ? RoadTrackingFilterGraphTest.fourZeros
          : RoadTrackingFilterGraphTest.twoZeros, stateErrorSS
          .getMean().toArray(), 5d * Math
          .sqrt(trueVehicleState.getOnRoadModelCovarianceParam()
              .getValue().normFrobenius()));

      final Vector stateVelError =
          stateErrorSS.getMean().getDimensionality() == 4
              ? MotionStateEstimatorPredictor.getVg().times(
                  stateErrorSS.getMean()) : VectorFactory
                  .getDefault().copyValues(
                      MotionStateEstimatorPredictor.getVr()
                          .times(stateErrorSS.getMean())
                          .getElement(0));
      final Matrix trueV =
          truePathState.isOnRoad() ? MotionStateEstimatorPredictor
              .getVr() : MotionStateEstimatorPredictor.getVg();
      final Matrix trueTransCov =
          truePathState.isOnRoad() ? trueVehicleState
              .getOnRoadModelCovarianceParam().getValue()
              : trueVehicleState.getOnRoadModelCovarianceParam()
                  .getValue();
      final double trueVelCovTol =
          Math.sqrt(trueV.times(trueTransCov)
              .times(trueV.transpose()).normFrobenius());

      ArrayAsserts.assertArrayEquals(VectorFactory.getDefault()
          .createVector(stateVelError.getDimensionality()).toArray(),
          stateVelError.toArray(), 4d * trueVelCovTol);

      ArrayAsserts
          .assertArrayEquals(hasPriorOnVariances
              ? RoadTrackingFilterGraphTest.oneZero
              : RoadTrackingFilterGraphTest.fourZeros,
              onRoadCovErrorSS.getMean().toArray(),
              0.7d * trueVehicleState.getOnRoadModelCovarianceParam()
                  .getValue().normFrobenius());

      ArrayAsserts.assertArrayEquals(hasPriorOnVariances
          ? RoadTrackingFilterGraphTest.fourZeros
          : RoadTrackingFilterGraphTest.sixteenZeros,
          offRoadCovErrorSS.getMean().toArray(),
          0.7d * trueVehicleState.getOffRoadModelCovarianceParam()
              .getValue().normFrobenius());
    }
  }

}