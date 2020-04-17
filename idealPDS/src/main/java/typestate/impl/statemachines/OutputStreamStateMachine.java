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
package typestate.impl.statemachines;

import boomerang.WeightedForwardQuery;
import boomerang.scene.Statement;
import java.util.Collection;
import typestate.TransitionFunction;
import typestate.finiteautomata.MatcherTransition;
import typestate.finiteautomata.MatcherTransition.Parameter;
import typestate.finiteautomata.MatcherTransition.Type;
import typestate.finiteautomata.State;
import typestate.finiteautomata.TypeStateMachineWeightFunctions;

public class OutputStreamStateMachine extends TypeStateMachineWeightFunctions {

  private static final String CLOSE_METHODS = ".* close.*";
  private static final String WRITE_METHODS = ".* write.*";
  private static final String TYPE = "java.io.OutputStream";;

  public static enum States implements State {
    NONE,
    CLOSED,
    ERROR;

    @Override
    public boolean isErrorState() {
      return this == ERROR;
    }

    @Override
    public boolean isInitialState() {
      return false;
    }

    @Override
    public boolean isAccepting() {
      return false;
    }
  }

  public OutputStreamStateMachine() {
    addTransition(
        new MatcherTransition(
            States.NONE, CLOSE_METHODS, Parameter.This, States.CLOSED, Type.OnReturn));
    addTransition(
        new MatcherTransition(
            States.CLOSED, CLOSE_METHODS, Parameter.This, States.CLOSED, Type.OnReturn));
    addTransition(
        new MatcherTransition(
            States.CLOSED, WRITE_METHODS, Parameter.This, States.ERROR, Type.OnReturn));
    addTransition(
        new MatcherTransition(
            States.ERROR, WRITE_METHODS, Parameter.This, States.ERROR, Type.OnReturn));
  }

  @Override
  public Collection<WeightedForwardQuery<TransitionFunction>> generateSeed(Statement unit) {
    return generateThisAtAnyCallSitesOf(unit, TYPE, CLOSE_METHODS);
  }

  @Override
  protected State initialState() {
    return States.NONE;
  }
}
