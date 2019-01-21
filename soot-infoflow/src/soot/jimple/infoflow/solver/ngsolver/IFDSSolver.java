/*******************************************************************************
 * Copyright (c) 2012 Eric Bodden.
 * Copyright (c) 2013 Tata Consultancy Services & Ecole Polytechnique de Montreal
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Eric Bodden - initial API and implementation
 *     Marc-Andre Laverdiere-Papineau - Fixed race condition
 *     Steven Arzt - Created FastSolver implementation
 ******************************************************************************/
package soot.jimple.infoflow.solver.ngsolver;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;

import heros.DontSynchronize;
import heros.SynchronizedBy;
import heros.solver.Pair;
import heros.solver.PathEdge;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.collect.ConcurrentHashSet;
import soot.jimple.infoflow.collect.MyConcurrentHashMap;
import soot.jimple.infoflow.memory.IMemoryBoundedSolver;
import soot.jimple.infoflow.memory.ISolverTerminationReason;
import soot.jimple.infoflow.solver.FastSolverLinkedNode;
import soot.jimple.infoflow.solver.PredecessorShorteningMode;
import soot.jimple.infoflow.solver.executors.InterruptableExecutor;
import soot.jimple.infoflow.solver.executors.SetPoolExecutor;
import soot.jimple.infoflow.solver.memory.IMemoryManager;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;

/**
 * A solver for an {@link IFDSTabulationProblem}. This solver is not based on
 * the IDESolver implementation in Heros for performance reasons.
 * 
 * @param <N> The type of nodes in the interprocedural control-flow graph.
 *        Typically {@link Unit}.
 * @param <D> The type of data-flow facts to be computed by the tabulation
 *        problem.
 * @param <I> The type of inter-procedural control-flow graph being used.
 * @see IFDSTabulationProblem
 */
public class IFDSSolver<N, D extends FastSolverLinkedNode<N>, I extends BiDiInterproceduralCFG<N, SootMethod>>
		implements IMemoryBoundedSolver {

	/**
	 * The phases supported by the data flow solver
	 */
	public enum DataFlowSolverPhase {
			/**
			 * In the first phase, the taint abstractions are propagated regardless of
			 * source information
			 */
			FIRST_PHASE,

			/**
			 * In the second phase, actual sources are propagated over the method summaries
			 * generated in the first phase
			 */
			SECOND_PHASE
	}

	public static CacheBuilder<Object, Object> DEFAULT_CACHE_BUILDER = CacheBuilder.newBuilder()
			.concurrencyLevel(Runtime.getRuntime().availableProcessors()).initialCapacity(10000).softValues();

	protected static final Logger logger = LoggerFactory.getLogger(IFDSSolver.class);

	protected InterruptableExecutor executor;

	@SynchronizedBy("thread safe data structure, only modified internally")
	protected final I icfg;

	// stores summaries that were queried before they were computed
	// see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on 'incoming'")
	protected final MyConcurrentHashMap<Pair<SootMethod, D>, Set<Pair<N, D>>> endSummary = new MyConcurrentHashMap<Pair<SootMethod, D>, Set<Pair<N, D>>>();

	// edges going along calls
	// see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on field")
	protected final MyConcurrentHashMap<Pair<SootMethod, D>, MyConcurrentHashMap<N, Map<D, D>>> incoming = new MyConcurrentHashMap<Pair<SootMethod, D>, MyConcurrentHashMap<N, Map<D, D>>>();

	@DontSynchronize("stateless")
	protected final FlowFunctions<N, D, SootMethod> flowFunctions;

	@DontSynchronize("only used by single thread")
	protected final Map<N, Set<D>> initialSeeds;

	@DontSynchronize("benign races")
	public long propagationCount;

	@DontSynchronize("stateless")
	protected final D zeroValue;

	@DontSynchronize("readOnly")
	protected final FlowFunctionCache<N, D, SootMethod> ffCache;

	@DontSynchronize("readOnly")
	protected final boolean followReturnsPastSeeds;

	@DontSynchronize("readOnly")
	private int maxJoinPointAbstractions = -1;

	@DontSynchronize("readOnly")
	protected IMemoryManager<D, N> memoryManager = null;

	protected boolean solverId;

	private Set<IMemoryBoundedSolverStatusNotification> notificationListeners = new HashSet<>();
	private ISolverTerminationReason killFlag = null;

	private int maxCalleesPerCallSite = 75;
	private int maxAbstractionPathLength = 100;

	private DataFlowSolverPhase solverPhase = DataFlowSolverPhase.FIRST_PHASE;

	/**
	 * Creates a solver for the given problem, which caches flow functions and edge
	 * functions. The solver must then be started by calling {@link #solve()}.
	 */
	public IFDSSolver(IFDSTabulationProblem<N, D, SootMethod, I> tabulationProblem) {
		this(tabulationProblem, DEFAULT_CACHE_BUILDER);
	}

	/**
	 * Creates a solver for the given problem, constructing caches with the given
	 * {@link CacheBuilder}. The solver must then be started by calling
	 * {@link #solve()}.
	 * 
	 * @param tabulationProblem        The tabulation problem to solve
	 * @param flowFunctionCacheBuilder A valid {@link CacheBuilder} or
	 *                                 <code>null</code> if no caching is to be used
	 *                                 for flow functions.
	 */
	public IFDSSolver(IFDSTabulationProblem<N, D, SootMethod, I> tabulationProblem,
			@SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder) {
		if (logger.isDebugEnabled())
			flowFunctionCacheBuilder = flowFunctionCacheBuilder.recordStats();
		this.zeroValue = tabulationProblem.zeroValue();
		this.icfg = tabulationProblem.interproceduralCFG();
		FlowFunctions<N, D, SootMethod> flowFunctions = tabulationProblem.flowFunctions();
		if (flowFunctionCacheBuilder != null) {
			ffCache = new FlowFunctionCache<N, D, SootMethod>(flowFunctions, flowFunctionCacheBuilder);
			flowFunctions = ffCache;
		} else {
			ffCache = null;
		}
		this.flowFunctions = flowFunctions;
		this.initialSeeds = tabulationProblem.initialSeeds();
		this.followReturnsPastSeeds = tabulationProblem.followReturnsPastSeeds();
		this.executor = getExecutor();
	}

	public void setSolverId(boolean solverId) {
		this.solverId = solverId;
	}

	/**
	 * Runs the solver on the configured problem. This can take some time.
	 */
	public void solve() {
		reset();

		if (solverPhase == DataFlowSolverPhase.SECOND_PHASE)
			removeEndSummariesWithContext();

		// Notify the listeners that the solver has been started
		for (IMemoryBoundedSolverStatusNotification listener : notificationListeners)
			listener.notifySolverStarted(this);

		submitInitialSeeds();
		awaitCompletionComputeValuesAndShutdown();

		// Notify the listeners that the solver has been terminated
		for (IMemoryBoundedSolverStatusNotification listener : notificationListeners)
			listener.notifySolverTerminated(this);
	}

	/**
	 * Removes all end summaries that have a source context
	 */
	private void removeEndSummariesWithContext() {
		for (Set<Pair<N, D>> pairSet : endSummary.values()) {
			for (Iterator<Pair<N, D>> pairIt = pairSet.iterator(); pairIt.hasNext();) {
				Pair<N, D> pair = pairIt.next();
				if (pair.getO2().getSourceContext() != null)
					pairIt.remove();
			}
		}
	}

	/**
	 * Schedules the processing of initial seeds, initiating the analysis. Clients
	 * should only call this methods if performing synchronization on their own.
	 * Normally, {@link #solve()} should be called instead.
	 */
	protected void submitInitialSeeds() {
		for (Entry<N, Set<D>> seed : initialSeeds.entrySet()) {
			N startPoint = seed.getKey();
			for (D val : seed.getValue())
				propagate(new SolverState<>(zeroValue, startPoint, val), null, false);
		}
	}

	/**
	 * Awaits the completion of the exploded super graph. When complete, computes
	 * result values, shuts down the executor and returns.
	 */
	protected void awaitCompletionComputeValuesAndShutdown() {
		{
			// run executor and await termination of tasks
			runExecutorAndAwaitCompletion();
		}
		if (logger.isDebugEnabled())
			printStats();

		// ask executor to shut down;
		// this will cause new submissions to the executor to be rejected,
		// but at this point all tasks should have completed anyway
		executor.shutdown();

		// Wait for the executor to be really gone
		while (!executor.isTerminated()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// silently ignore the exception, it's not an issue if the
				// thread gets aborted
			}
		}
	}

	/**
	 * Runs execution, re-throwing exceptions that might be thrown during its
	 * execution.
	 */
	private void runExecutorAndAwaitCompletion() {
		try {
			executor.awaitCompletion();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		Throwable exception = executor.getException();
		if (exception != null) {
			throw new RuntimeException("There were exceptions during IFDS analysis. Exiting.", exception);
		}
	}

	/**
	 * Dispatch the processing of a given edge. It may be executed in a different
	 * thread.
	 * 
	 * @param edge the edge to process
	 */
	protected void scheduleEdgeProcessing(SolverState<N, D> state) {
		// If the executor has been killed, there is little point
		// in submitting new tasks
		if (killFlag != null || executor.isTerminating() || executor.isTerminated())
			return;

		executor.execute(new PathEdgeProcessingTask(state, solverId));
		propagationCount++;
	}

	@SynchronizedBy("thread safe data structure, consistent locking when used")
	protected MyConcurrentHashMap<PathEdge<N, D>, D> jumpFunctions = new MyConcurrentHashMap<PathEdge<N, D>, D>();

	/**
	 * Lines 13-20 of the algorithm; processing a call site in the caller's context.
	 * 
	 * For each possible callee, registers incoming call edges. Also propagates
	 * call-to-return flows and summarized callee flows within the caller.
	 * 
	 * @param edge an edge whose target node resembles a method call
	 */
	private void processCall(SolverState<N, D> state) {
		final D d1 = state.sourceVal;
		final N n = state.target; // a call node; line 14...
		final D d2 = state.targetVal;

		Collection<N> returnSiteNs = icfg.getReturnSitesOfCallAt(n);

		// for each possible callee
		Collection<SootMethod> callees = icfg.getCalleesOfCallAt(n);
		if (maxCalleesPerCallSite < 0 || callees.size() <= maxCalleesPerCallSite) {
			callees.stream().filter(m -> m.isConcrete()).forEach(new Consumer<SootMethod>() {

				@Override
				public void accept(SootMethod sCalledProcN) {
					// Early termination check
					if (killFlag != null)
						return;

					// compute the call-flow function
					FlowFunction<N, D> function = flowFunctions.getCallFlowFunction(n, sCalledProcN);
					Set<D> res = computeCallFlowFunction(function, state);

					if (res != null && !res.isEmpty()) {
						Collection<N> startPointsOf = icfg.getStartPointsOf(sCalledProcN);
						// for each result node of the call-flow function
						for (D d3 : res) {
							if (memoryManager != null)
								d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
							if (d3 == null)
								continue;

							// register the fact that <sp,d3> has an incoming edge from
							// <n,d2>
							// line 15.1 of Naeem/Lhotak/Rodriguez
//							if (!addIncoming(sCalledProcN, d3.reduce(), n, d1.reduce(), d2.reduce()))
							int incomingFlags = addIncoming(sCalledProcN, d3, n, d1, d2);
							if ((incomingFlags & FLAG_NEW_INCOMING) == 0x0)
								continue;

							// If we already have a summary, there is no need to analyze the method again
							if (applyEndSummaryOnCall(state, returnSiteNs, sCalledProcN, d3))
								continue;

							// No need to propagate anything if we already know the callee
							if ((incomingFlags & FLAG_NEW_CALLEE) == 0x0)
								continue;

							// for each callee's start point(s)
							for (N sP : startPointsOf) {
								// create initial self-loop
								propagate(new SolverState<>(d3, sP, d3), n, false); // line 15
							}
						}
					}
				}

			});
		}

		// line 17-19 of Naeem/Lhotak/Rodriguez
		// process intra-procedural flows along call-to-return flow functions
		for (N returnSiteN : returnSiteNs) {
			FlowFunction<N, D> callToReturnFlowFunction = flowFunctions.getCallToReturnFlowFunction(n, returnSiteN);
			Set<D> res = computeCallToReturnFlowFunction(callToReturnFlowFunction, state);
			if (res != null && !res.isEmpty()) {
				for (D d3 : res) {
					if (memoryManager != null)
						d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
					if (d3 != null)
						propagate(state.derive(returnSiteN, d3), n, false);
				}
			}
		}
	}

	protected boolean applyEndSummaryOnCall(SolverState<N, D> state, Collection<N> returnSiteNs,
			SootMethod sCalledProcN, D d3) {
		final N n = state.getTarget();
		final D d1 = state.getSourceVal();

		// line 15.2
		Set<Pair<N, D>> endSumm = endSummary(sCalledProcN, d3);

		// still line 15.2 of Naeem/Lhotak/Rodriguez
		// for each already-queried exit value <eP,d4> reachable
		// from <sP,d3>, create new caller-side jump functions to
		// the return sites because we have observed a potentially
		// new incoming edge into <sP,d3>
		boolean hasPropagated = false;
		if (endSumm != null && !endSumm.isEmpty()) {
			for (Pair<N, D> entry : endSumm) {
				N eP = entry.getO1();
				D d4 = entry.getO2();
				// for each return site
				for (N retSiteN : returnSiteNs) {
					// compute return-flow function
					FlowFunction<N, D> retFunction = flowFunctions.getReturnFlowFunction(n, sCalledProcN, eP, retSiteN);
					Set<D> retFlowRes = computeReturnFlowFunction(retFunction, new SolverState<N, D>(d3, retSiteN, d4),
							n, Collections.singleton(d1));
					if (retFlowRes != null && !retFlowRes.isEmpty()) {
						// for each target value of the function
						for (D d5 : retFlowRes) {
							if (memoryManager != null)
								d5 = memoryManager.handleGeneratedMemoryObject(d4, d5);
							propagate(state.derive(retSiteN, d5), n, false);
							hasPropagated = true;
						}
					}
				}
			}
		}
		return hasPropagated;
	}

	private Set<D> propagateSourceContext(SolverState<N, D> state, Set<D> targets) {
		if (targets != null && !targets.isEmpty() && solverPhase == DataFlowSolverPhase.SECOND_PHASE) {
			for (D d : targets)
				d.deriveSourceContext(state.getTargetVal());
		}
		return targets;
	}

	/**
	 * Computes the call flow function for the given call-site abstraction
	 * 
	 * @param callFlowFunction The call flow function to compute
	 * @param d1               The abstraction at the current method's start node.
	 * @param d2               The abstraction at the call site
	 * @return The set of caller-side abstractions at the callee's start node
	 */
	protected Set<D> computeCallFlowFunction(FlowFunction<N, D> callFlowFunction, SolverState<N, D> state) {
		Set<D> targets = callFlowFunction.computeTargets(state);
		return propagateSourceContext(state, targets);
	}

	/**
	 * Computes the call-to-return flow function for the given call-site abstraction
	 * 
	 * @param callToReturnFlowFunction The call-to-return flow function to compute
	 * @param d1                       The abstraction at the current method's start
	 *                                 node.
	 * @param d2                       The abstraction at the call site
	 * @return The set of caller-side abstractions at the return site
	 */
	protected Set<D> computeCallToReturnFlowFunction(FlowFunction<N, D> callToReturnFlowFunction,
			SolverState<N, D> state) {
		Set<D> targets = callToReturnFlowFunction.computeTargets(state);
		return propagateSourceContext(state, targets);
	}

	/**
	 * Lines 21-32 of the algorithm.
	 * 
	 * Stores callee-side summaries. Also, at the side of the caller, propagates
	 * intra-procedural flows to return sites using those newly computed summaries.
	 * 
	 * @param edge an edge whose target node resembles a method exits
	 */
	protected void processExit(SolverState<N, D> state) {
		final N n = state.target; // an exit node; line 21...
		SootMethod methodThatNeedsSummary = icfg.getMethodOf(n);

		final D d1 = state.sourceVal;
		final D d2 = state.targetVal;

		// for each of the method's start points, determine incoming calls

		// line 21.1 of Naeem/Lhotak/Rodriguez
		// register end-summary
		if (!addEndSummary(methodThatNeedsSummary, d1, n, d2))
			return;
		Map<N, Map<D, D>> inc = incoming(d1, methodThatNeedsSummary);

		// for each incoming call edge already processed
		// (see processCall(..))
		if (inc != null && !inc.isEmpty()) {
			for (Entry<N, Map<D, D>> entry : inc.entrySet()) {
				// Early termination check
				if (killFlag != null)
					return;

				// line 22
				N c = entry.getKey();
				Set<D> callerSideDs = entry.getValue().keySet();
				// for each return site
				for (N retSiteC : icfg.getReturnSitesOfCallAt(c)) {
					// compute return-flow function
					FlowFunction<N, D> retFunction = flowFunctions.getReturnFlowFunction(c, methodThatNeedsSummary, n,
							retSiteC);
					Set<D> targets = computeReturnFlowFunction(retFunction, state, n, callerSideDs);
					// for each incoming-call value
					if (targets != null && !targets.isEmpty()) {
						for (Entry<D, D> d1d2entry : entry.getValue().entrySet()) {
							final D d4 = d1d2entry.getKey();
							for (D d5 : targets) {
								if (memoryManager != null)
									d5 = memoryManager.handleGeneratedMemoryObject(d2, d5);
								if (d5 == null)
									continue;

//								final PathEdge<N, D> edge = new PathEdge<N, D>(d4, retSiteC, d5);
//								if (jumpFunctions.putIfAbsent(edge, edge.factAtTarget()) != null)
//									System.out.println("x");

								propagate(new SolverState<>(d4, retSiteC, d5), c, false);
							}
						}
					}
				}
			}
		}

		// handling for unbalanced problems where we return out of a method with
		// a fact for which we have no incoming flow
		// note: we propagate that way only values that originate from ZERO, as
		// conditionally generated values should only be propagated into callers that
		// have an incoming edge for this condition
		if (followReturnsPastSeeds && d1 == zeroValue && (inc == null || inc.isEmpty())) {
			Collection<N> callers = icfg.getCallersOf(methodThatNeedsSummary);
			for (N c : callers) {
				for (N retSiteC : icfg.getReturnSitesOfCallAt(c)) {
					FlowFunction<N, D> retFunction = flowFunctions.getReturnFlowFunction(c, methodThatNeedsSummary, n,
							retSiteC);
					Set<D> targets = computeReturnFlowFunction(retFunction, state, c, Collections.singleton(zeroValue));
					if (targets != null && !targets.isEmpty()) {
						for (D d5 : targets) {
							if (memoryManager != null)
								d5 = memoryManager.handleGeneratedMemoryObject(d2, d5);
							if (d5 != null)
								propagate(new SolverState<>(zeroValue, retSiteC, d5), c, true);
						}
					}
				}
			}
			// in cases where there are no callers, the return statement would
			// normally not be processed at all; this might be undesirable if the flow
			// function has a side effect such as registering a taint; instead we thus call
			// the return flow function will a null caller
			if (callers.isEmpty()) {
				FlowFunction<N, D> retFunction = flowFunctions.getReturnFlowFunction(null, methodThatNeedsSummary, n,
						null);
				retFunction.computeTargets(state);
			}
		}
	}

	/**
	 * Computes the return flow function for the given set of caller-side
	 * abstractions.
	 * 
	 * @param retFunction  The return flow function to compute
	 * @param d1           The abstraction at the beginning of the callee
	 * @param d2           The abstraction at the exit node in the callee
	 * @param callSite     The call site
	 * @param callerSideDs The abstractions at the call site
	 * @return The set of caller-side abstractions at the return site
	 */
	protected Set<D> computeReturnFlowFunction(FlowFunction<N, D> retFunction, SolverState<N, D> state, N callSite,
			Collection<D> callerSideDs) {
		Set<D> targets = retFunction.computeTargets(state);
		return propagateSourceContext(state, targets);
	}

	/**
	 * Lines 33-37 of the algorithm. Simply propagate normal, intra-procedural
	 * flows.
	 * 
	 * @param edge
	 */
	private void processNormalFlow(SolverState<N, D> state) {
		final N n = state.target;
		final D d2 = state.targetVal;

		for (N m : icfg.getSuccsOf(n)) {
			// Early termination check
			if (killFlag != null)
				return;

			// Compute the flow function
			FlowFunction<N, D> flowFunction = flowFunctions.getNormalFlowFunction(n, m);
			Set<D> res = computeNormalFlowFunction(flowFunction, state);
			if (res != null && !res.isEmpty()) {
				for (D d3 : res) {
					if (memoryManager != null && d2 != d3)
						d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
					if (d3 != null)
						propagate(state.derive(m, d3), null, false);
				}
			}
		}
	}

	/**
	 * Computes the normal flow function for the given set of start and end
	 * abstractions.
	 * 
	 * @param flowFunction The normal flow function to compute
	 * @param d1           The abstraction at the method's start node
	 * @param d2           The abstraction at the current node
	 * @return The set of abstractions at the successor node
	 */
	protected Set<D> computeNormalFlowFunction(FlowFunction<N, D> flowFunction, SolverState<N, D> state) {
		Set<D> targets = flowFunction.computeTargets(state);
		return propagateSourceContext(state, targets);
	}

	/**
	 * Propagates the flow further down the exploded super graph.
	 * 
	 * @param sourceVal          the source value of the propagated summary edge
	 * @param target             the target statement
	 * @param targetVal          the target value at the target statement
	 * @param relatedCallSite    for call and return flows the related call
	 *                           statement, <code>null</code> otherwise (this value
	 *                           is not used within this implementation but may be
	 *                           useful for subclasses of {@link IFDSSolver})
	 * @param isUnbalancedReturn <code>true</code> if this edge is propagating an
	 *                           unbalanced return (this value is not used within
	 *                           this implementation but may be useful for
	 *                           subclasses of {@link IFDSSolver})
	 */
	protected void propagate(SolverState<N, D> state, /* deliberately exposed to clients */ N relatedCallSite,
			/* deliberately exposed to clients */ boolean isUnbalancedReturn) {
		D sourceVal = state.sourceVal;
		N target = state.target;
		D targetVal = state.targetVal;

		// Let the memory manager run
		if (memoryManager != null) {
			sourceVal = memoryManager.handleMemoryObject(sourceVal);
			targetVal = memoryManager.handleMemoryObject(targetVal);
			if (sourceVal == null || targetVal == null)
				return;
		}

		// Check the path length
		if (maxAbstractionPathLength >= 0 && targetVal.getPathLength() > maxAbstractionPathLength)
			return;

		// Have we seen this abstraction before?
		final PathEdge<N, D> edge = new PathEdge<N, D>(sourceVal, target, targetVal);
		final D oldTargetVal = (solverId ? state.jumpFunctionsForward : state.jumpFunctionsBackward).putIfAbsent(edge,
				targetVal);
		if (oldTargetVal == null) {
			// This jump function is new, so we need to propagate it onwards
			scheduleEdgeProcessing(state);
		}
	}

	protected Set<Pair<N, D>> endSummary(SootMethod m, D d3) {
		Set<Pair<N, D>> map = endSummary.get(new Pair<SootMethod, D>(m, d3));
		return map;
	}

	private boolean addEndSummary(SootMethod m, D d1, N eP, D d2) {
		Set<Pair<N, D>> summaries = endSummary.putIfAbsentElseGet(new Pair<SootMethod, D>(m, d1),
				new ConcurrentHashSet<Pair<N, D>>());
		return summaries.add(new Pair<N, D>(eP, d2));
	}

	protected Map<N, Map<D, D>> incoming(D d1, SootMethod m) {
		Map<N, Map<D, D>> map = incoming.get(new Pair<SootMethod, D>(m, d1));
		return map;
	}

	private final int FLAG_NEW_CALLEE = 0x01;
	private final int FLAG_NEW_INCOMING = 0x02;

	protected int addIncoming(SootMethod m, D d3, N n, D d1, D d2) {
		int res = 0x0;
		MyConcurrentHashMap<N, Map<D, D>> newSet = new MyConcurrentHashMap<>();
		MyConcurrentHashMap<N, Map<D, D>> summaries = incoming.putIfAbsentElseGet(new Pair<SootMethod, D>(m, d3),
				newSet);
		Map<D, D> set = summaries.putIfAbsentElseGet(n, new ConcurrentHashMap<D, D>());
		if (set.put(d1, d2) == null)
			res |= FLAG_NEW_INCOMING;
		if (summaries == newSet)
			res |= FLAG_NEW_CALLEE;
		return res;
	}

	/**
	 * Factory method for this solver's thread-pool executor.
	 */
	protected InterruptableExecutor getExecutor() {
		int numThreads = Runtime.getRuntime().availableProcessors() - 1;
		SetPoolExecutor executor = new SetPoolExecutor(1, numThreads, 30, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>());
		executor.setThreadFactory(new ThreadFactory() {

			@Override
			public Thread newThread(Runnable r) {
				Thread thrIFDS = new Thread(r);
				thrIFDS.setDaemon(true);
				thrIFDS.setName("IFDS Solver");
				return thrIFDS;
			}
		});
		return executor;
	}

	/**
	 * Returns a String used to identify the output of this solver in debug mode.
	 * Subclasses can overwrite this string to distinguish the output from different
	 * solvers.
	 */
	protected String getDebugName() {
		return "FAST IFDS SOLVER";
	}

	public void printStats() {
		if (logger.isDebugEnabled()) {
			if (ffCache != null)
				ffCache.printStats();
		} else {
			logger.info("No statistics were collected, as DEBUG is disabled.");
		}
	}

	private class PathEdgeProcessingTask implements Runnable {

		private final SolverState<N, D> state;
		private final boolean solverId;

		public PathEdgeProcessingTask(SolverState<N, D> state, boolean solverId) {
			this.state = state;
			this.solverId = solverId;
		}

		public void run() {
			final N target = state.target;
			if (icfg.isCallStmt(target)) {
				processCall(state);
			} else {
				// note that some statements, such as "throw" may be
				// both an exit statement and a "normal" statement
				if (icfg.isExitStmt(target))
					processExit(state);
				if (!icfg.getSuccsOf(target).isEmpty())
					processNormalFlow(state);
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((state == null) ? 0 : state.hashCode());
			result = prime * result + (solverId ? 1231 : 1237);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PathEdgeProcessingTask other = (PathEdgeProcessingTask) obj;
			if (state == null) {
				if (other.state != null)
					return false;
			} else if (!state.equals(other.state))
				return false;
			if (solverId != other.solverId)
				return false;
			return true;
		}

	}

	/**
	 * Sets whether abstractions on method returns shall be connected to the
	 * respective call abstractions to shortcut paths.
	 * 
	 * @param mode The strategy to use for shortening predecessor paths
	 */
	public void setPredecessorShorteningMode(PredecessorShorteningMode mode) {
		// this.shorteningMode = mode;
	}

	/**
	 * Sets the maximum number of abstractions that shall be recorded per join
	 * point. In other words, enabling this option disables the recording of
	 * neighbors beyond the given count.
	 * 
	 * @param maxJoinPointAbstractions The maximum number of abstractions per join
	 *                                 point, or -1 to record an arbitrary number of
	 *                                 join point abstractions
	 */
	public void setMaxJoinPointAbstractions(int maxJoinPointAbstractions) {
		this.maxJoinPointAbstractions = maxJoinPointAbstractions;
	}

	/**
	 * Sets the memory manager that shall be used to manage the abstractions
	 * 
	 * @param memoryManager The memory manager that shall be used to manage the
	 *                      abstractions
	 */
	public void setMemoryManager(IMemoryManager<D, N> memoryManager) {
		this.memoryManager = memoryManager;
	}

	/**
	 * Gets the memory manager used by this solver to reduce memory consumption
	 * 
	 * @return The memory manager registered with this solver
	 */
	public IMemoryManager<D, N> getMemoryManager() {
		return this.memoryManager;
	}

	@Override
	public void forceTerminate(ISolverTerminationReason reason) {
		this.killFlag = reason;
		this.executor.interrupt();
		this.executor.shutdown();
	}

	@Override
	public boolean isTerminated() {
		return killFlag != null || this.executor.isFinished();
	}

	@Override
	public boolean isKilled() {
		return killFlag != null;
	}

	@Override
	public void reset() {
		this.killFlag = null;
	}

	@Override
	public void addStatusListener(IMemoryBoundedSolverStatusNotification listener) {
		this.notificationListeners.add(listener);
	}

	@Override
	public ISolverTerminationReason getTerminationReason() {
		return killFlag;
	}

	public void setMaxCalleesPerCallSite(int maxCalleesPerCallSite) {
		this.maxCalleesPerCallSite = maxCalleesPerCallSite;
	}

	public void setSolverPhase(DataFlowSolverPhase solverPhase) {
		this.solverPhase = solverPhase;
	}

	public void setExecutor(InterruptableExecutor executor) {
		this.executor = executor;
	}

	public void resetStatistics() {
		this.propagationCount = 0;
	}

}
