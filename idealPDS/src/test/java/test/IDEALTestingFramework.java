/**
 * ***************************************************************************** Copyright (c) 2018
 * Fraunhofer IEM, Paderborn, Germany. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * <p>SPDX-License-Identifier: EPL-2.0
 *
 * <p>Contributors: Johannes Spaeth - initial API and implementation
 * *****************************************************************************
 */
package test;

import boomerang.BoomerangOptions;
import boomerang.DataFlowScope;
import boomerang.DefaultBoomerangOptions;
import boomerang.WeightedForwardQuery;
import boomerang.callgraph.CallGraph;
import boomerang.callgraph.CallGraph.Edge;
import boomerang.debugger.Debugger;
import boomerang.example.WALAAnalysis;
import boomerang.results.ForwardBoomerangResults;
import boomerang.scene.CallSiteStatement;
import boomerang.scene.Method;
import boomerang.scene.Statement;
import boomerang.scene.Val;
import boomerang.scene.jimple.BoomerangPretransformer;
import boomerang.scene.jimple.JimpleMethod;
import boomerang.scene.jimple.SootCallGraph;
import boomerang.scene.jimple.SootDataFlowScope;
import boomerang.scene.wala.WALACallGraph;
import boomerang.scene.wala.WALAMethod;
import com.google.common.collect.Lists;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.FileProvider;
import ideal.IDEALAnalysis;
import ideal.IDEALAnalysisDefinition;
import ideal.IDEALResultHandler;
import ideal.IDEALSeedSolver;
import ideal.StoreIDEALResultHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import soot.Scene;
import soot.SceneTransformer;
import sync.pds.solver.WeightFunctions;
import test.ExpectedResults.InternalState;
import test.core.selfrunning.AbstractTestingFramework;
import test.core.selfrunning.ImprecisionException;
import typestate.TransitionFunction;
import typestate.finiteautomata.TypeStateMachineWeightFunctions;

public abstract class IDEALTestingFramework extends AbstractTestingFramework {
  private static final boolean FAIL_ON_IMPRECISE = false;
  private static final boolean VISUALIZATION = false;
  private static final boolean WALA = false;
  protected StoreIDEALResultHandler<TransitionFunction> resultHandler =
      new StoreIDEALResultHandler<>();
  protected CallGraph callGraph;
  protected DataFlowScope dataFlowScope;

  @Rule public TestName walatestMethodName = new TestName();

  protected abstract TypeStateMachineWeightFunctions getStateMachine();

  protected IDEALAnalysis<TransitionFunction> createAnalysis() {
    return new IDEALAnalysis<TransitionFunction>(
        new IDEALAnalysisDefinition<TransitionFunction>() {

          @Override
          public Collection<WeightedForwardQuery<TransitionFunction>> generate(Statement stmt) {
            return IDEALTestingFramework.this.getStateMachine().generateSeed(stmt);
          }

          @Override
          public WeightFunctions<Statement, Val, Statement, TransitionFunction> weightFunctions() {
            return IDEALTestingFramework.this.getStateMachine();
          }

          @Override
          public Debugger<TransitionFunction> debugger(IDEALSeedSolver<TransitionFunction> solver) {
            return
            /**
             * VISUALIZATION ? new IDEVizDebugger<>(new File(
             * ideVizFile.getAbsolutePath().replace(".json", " " + solver.getSeed() + ".json")),
             * callGraph) :
             */
            new Debugger<>();
          }

          @Override
          public IDEALResultHandler<TransitionFunction> getResultHandler() {
            return resultHandler;
          }

          @Override
          public BoomerangOptions boomerangOptions() {
            return new DefaultBoomerangOptions() {

              @Override
              public boolean onTheFlyCallGraph() {
                return false;
              }

              public StaticFieldStrategy getStaticFieldStrategy() {
                return StaticFieldStrategy.FLOW_SENSITIVE;
              };
            };
          }

          @Override
          public CallGraph callGraph() {
            return callGraph;
          }

          @Override
          protected DataFlowScope getDataFlowScope() {
            return dataFlowScope;
          }
        });
  }

  @Before
  public void beforeTestCaseExecution() {
    if (WALA) {
      runWithWala();
      // To never execute the @Test method...
      org.junit.Assume.assumeTrue(false);
      return;
    }
    super.beforeTestCaseExecution();
  }

  private void runWithWala() {
    AnalysisScope walaScope;
    try {
      walaScope =
          AnalysisScopeReader.readJavaScope(
              "testScope.txt",
              (new FileProvider()).getFile("exclusion.txt"),
              WALAAnalysis.class.getClassLoader());
      IClassHierarchy cha = ClassHierarchyFactory.make(walaScope);
      String testCaseClassName = getTestCaseClassName().replace(".", "/").replace("class ", "");

      final MethodReference ref =
          MethodReference.findOrCreate(
              ClassLoaderReference.Application,
              "L" + testCaseClassName,
              walatestMethodName.getMethodName(),
              "()V");

      IMethod method = cha.resolveMethod(ref);
      Iterable<Entrypoint> singleton =
          new Iterable<Entrypoint>() {

            @Override
            public Iterator<Entrypoint> iterator() {
              ArrayList<Entrypoint> list = Lists.newArrayList();
              list.add(new DefaultEntrypoint(method, cha));
              Iterator<Entrypoint> ret = list.iterator();
              return ret;
            }
          };
      AnalysisOptions options = new AnalysisOptions(walaScope, singleton);
      IAnalysisCacheView cache = new AnalysisCacheImpl();
      //		      CallGraphBuilder<InstanceKey> rtaBuilder =
      // Util.makeZeroOneCFABuilder(Language.JAVA, options, cache, cha, walaScope);
      CallGraphBuilder<InstanceKey> rtaBuilder =
          Util.makeRTABuilder(options, cache, cha, walaScope);
      com.ibm.wala.ipa.callgraph.CallGraph makeCallGraph = null;
      try {
        makeCallGraph = rtaBuilder.makeCallGraph(options, null);
      } catch (IllegalArgumentException | CallGraphBuilderCancelException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        //			}
        //			CHACallGraph CG = new CHACallGraph(cha);
        //			try {
        //				CG.init(singleton);
        //			} catch (CancelException e) {
        //				// TODO Auto-generated catch block
        //				e.printStackTrace();
      }
      System.out.println("Build call graph");
      callGraph = new WALACallGraph(makeCallGraph, cha);
      dataFlowScope = DataFlowScope.INCLUDE_ALL;
      System.out.println("Converted call graph");
      analyze(new WALAMethod(method, cache.getIR(method), cha));
    } catch (IOException | ClassHierarchyException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  protected SceneTransformer createAnalysisTransformer() throws ImprecisionException {
    return new SceneTransformer() {
      protected void internalTransform(
          String phaseName, @SuppressWarnings("rawtypes") Map options) {
        BoomerangPretransformer.v().reset();
        BoomerangPretransformer.v().apply();
        callGraph = new SootCallGraph();
        dataFlowScope = SootDataFlowScope.make(Scene.v());
        analyze(new JimpleMethod(sootTestMethod));
      }
    };
  }

  protected void analyze(Method m) {
    Set<Assertion> expectedResults = parseExpectedQueryResults(m);
    TestingResultReporter testingResultReporter = new TestingResultReporter(expectedResults);

    Map<WeightedForwardQuery<TransitionFunction>, ForwardBoomerangResults<TransitionFunction>>
        seedToSolvers = executeAnalysis();
    for (Entry<
            WeightedForwardQuery<TransitionFunction>, ForwardBoomerangResults<TransitionFunction>>
        e : seedToSolvers.entrySet()) {
      testingResultReporter.onSeedFinished(e.getKey().asNode(), e.getValue());
    }
    List<Assertion> unsound = Lists.newLinkedList();
    List<Assertion> imprecise = Lists.newLinkedList();
    for (Assertion r : expectedResults) {
      if (r instanceof ShouldNotBeAnalyzed) {
        throw new RuntimeException(r.toString());
      }
    }
    for (Assertion r : expectedResults) {
      if (!r.isSatisfied()) {
        unsound.add(r);
      }
    }
    for (Assertion r : expectedResults) {
      if (r.isImprecise()) {
        imprecise.add(r);
      }
    }
    if (!unsound.isEmpty()) throw new RuntimeException("Unsound results: " + unsound);
    if (!imprecise.isEmpty() && FAIL_ON_IMPRECISE) {
      throw new ImprecisionException("Imprecise results: " + imprecise);
    }
  }

  protected Map<
          WeightedForwardQuery<TransitionFunction>, ForwardBoomerangResults<TransitionFunction>>
      executeAnalysis() {
    IDEALTestingFramework.this.createAnalysis().run();
    return resultHandler.getResults();
  }

  private Set<Assertion> parseExpectedQueryResults(Method sootTestMethod) {
    Set<Assertion> results = new HashSet<>();
    parseExpectedQueryResults(sootTestMethod, results, new HashSet<Method>());
    return results;
  }

  private void parseExpectedQueryResults(Method m, Set<Assertion> queries, Set<Method> visited) {
    if (visited.contains(m)) return;
    visited.add(m);

    for (Statement stmt : m.getStatements()) {
      if (!(stmt.containsInvokeExpr())) continue;
      CallSiteStatement cs = (CallSiteStatement) stmt;
      for (Edge callSite : callGraph.edgesOutOf(stmt)) {
        parseExpectedQueryResults(callSite.tgt(), queries, visited);
      }
      boomerang.scene.InvokeExpr invokeExpr = stmt.getInvokeExpr();
      String invocationName = invokeExpr.getMethod().getName();
      if (invocationName.equals("shouldNotBeAnalyzed")) {
        queries.add(new ShouldNotBeAnalyzed(stmt));
      }
      if (!invocationName.startsWith("mayBeIn") && !invocationName.startsWith("mustBeIn")) continue;
      Val val = invokeExpr.getArg(0);
      if (invocationName.startsWith("mayBeIn")) {
        if (invocationName.contains("Error"))
          queries.add(new MayBe(cs.getReturnSiteStatement(), val, InternalState.ERROR));
        else queries.add(new MayBe(cs.getReturnSiteStatement(), val, InternalState.ACCEPTING));
      } else if (invocationName.startsWith("mustBeIn")) {
        if (invocationName.contains("Error"))
          queries.add(new MustBe(cs.getReturnSiteStatement(), val, InternalState.ERROR));
        else queries.add(new MustBe(cs.getReturnSiteStatement(), val, InternalState.ACCEPTING));
      }
    }
  }

  /**
   * The methods parameter describes the variable that a query is issued for. Note: We misuse
   * the @Deprecated annotation to highlight the method in the Code.
   */
  protected static void mayBeInErrorState(Object variable) {}

  protected static void mustBeInErrorState(Object variable) {}

  protected static void mayBeInAcceptingState(Object variable) {}

  protected static void mustBeInAcceptingState(Object variable) {}

  protected static void shouldNotBeAnalyzed() {}

  /**
   * This method can be used in test cases to create branching. It is not optimized away.
   *
   * @return
   */
  protected boolean staticallyUnknown() {
    return true;
  }
}
