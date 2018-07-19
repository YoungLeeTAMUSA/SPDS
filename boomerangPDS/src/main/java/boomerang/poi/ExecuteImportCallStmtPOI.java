package boomerang.poi;

import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.solver.ForwardBoomerangSolver;
import boomerang.solver.MethodBasedFieldTransitionListener;
import soot.SootMethod;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAStateListener;
import wpds.interfaces.WPAUpdateListener;

public class ExecuteImportCallStmtPOI<W extends Weight> extends AbstractExecuteImportPOI<W> {

	private boolean activate;
	private Set<Val> returningFacts = Sets.newHashSet();

	public ExecuteImportCallStmtPOI(AbstractBoomerangSolver<W> baseSolver, AbstractBoomerangSolver<W> flowSolver,
			Statement callSite, Statement returnStmt) {
		super(baseSolver, flowSolver, callSite, returnStmt);
	}

	public void solve(Val returningFact) {
		if(!returningFacts.add(returningFact)) {
			return;
		}
		
		if(activate) {
			return;
		}
		baseSolver.registerFieldTransitionListener(new MethodBasedFieldTransitionListener<W>(curr.getMethod()) {

			@Override
			public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
				final INode<Node<Statement, Val>> aliasedVariableAtStmt = t.getStart();
				if(activate)
					return;
				if (!t.getStart().fact().stmt().equals(curr))
					return;
				if (!(aliasedVariableAtStmt instanceof GeneratedState)) {
					Val alias = aliasedVariableAtStmt.fact().fact();
					if (alias.equals(returningFact) && t.getLabel().equals(Field.empty())) {
						// t.getTarget is the allocation site
						flowSolver.registerFieldTransitionListener(new FindNode(curr.getMethod(),returningFact));
					}
					if (alias.equals(returningFact) && !t.getLabel().equals(Field.empty())
							&& !t.getLabel().equals(Field.epsilon())) {
						flowAutomaton.registerListener(
								new HasOutTransitionWithSameLabel(new SingleNode<Node<Statement,Val>>(new Node<Statement,Val>(succ,returningFact)), t.getLabel(), t.getTarget()));
					}
				}
			}
		});
	}

	private class FindNode extends MethodBasedFieldTransitionListener<W> {

		private Val returningFact;

		public FindNode(SootMethod method, Val returningFact) {
			super(method);
			this.returningFact = returningFact;
		}

		@Override
		public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
			final INode<Node<Statement, Val>> aliasedVariableAtStmt = t.getStart();
			if(activate)
				return;
			if (!t.getStart().fact().stmt().equals(succ))
				return;
			if (!(aliasedVariableAtStmt instanceof GeneratedState)) {
				Val alias = aliasedVariableAtStmt.fact().fact();
				if (alias.equals(returningFact)) {
					activate(t);
				}
			}
		}
	}

	@Override
	protected void activate(Transition<Field, INode<Node<Statement, Val>>> aliasTrans) {
		if(activate)
			return;
		activate = true;
		baseSolver.getFieldAutomaton().registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {
			@Override
			public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
				if (!(t.getStart() instanceof GeneratedState) && t.getStart().fact().stmt().equals(succ)
						&& !flowSolver.valueUsedInStatement(curr.getUnit().get(), t.getStart().fact().fact())) {
					Val alias = t.getStart().fact().fact();
					Node<Statement, Val> aliasedVarAtSucc = new Node<Statement, Val>(succ, alias);
					flowSolver.setFieldContextReachable(aliasedVarAtSucc);
					for(Val fact : Lists.newArrayList(returningFacts)) {
						Node<Statement, Val> rightOpNode = new Node<Statement, Val>(succ, fact);
						flowSolver.addNormalCallFlow(rightOpNode, aliasedVarAtSucc);
					}
					importToFlowSolver(t, aliasTrans.getLabel(),aliasTrans.getTarget());
				}

			}
		});
	}

	protected void importToFlowSolver(Transition<Field, INode<Node<Statement, Val>>> t,
			Field aliasTransLabel,
			INode<Node<Statement, Val>> aliasTransTarget) {
		if (t.getLabel().equals(Field.empty())) {
			flowSolver.getFieldAutomaton().addTransition(new Transition<Field, INode<Node<Statement, Val>>>(
					t.getStart(), aliasTransLabel, aliasTransTarget));
		} else if (!t.getLabel().equals(Field.epsilon())) {
			flowSolver.getFieldAutomaton().addTransition(t);
		}
	}

}
