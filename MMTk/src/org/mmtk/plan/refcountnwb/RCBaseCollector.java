/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.refcountnwb;

import org.mmtk.plan.Phase;
import org.mmtk.plan.Plan;
import org.mmtk.plan.StopTheWorldCollector;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.refcountnwb.backuptrace.BTTraceLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.Log;
import org.mmtk.utility.deque.ObjectReferenceDeque;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.ObjectReference;

/**
 * This class implements the collector context for a simple reference counting
 * collector.
 */
@Uninterruptible
public abstract class RCBaseCollector extends StopTheWorldCollector {

  /************************************************************************
   * Initialization
   */
  protected final ObjectReferenceDeque newRootBuffer;
  private final BTTraceLocal backupTrace;
  private final ObjectReferenceDeque modBuffer;
  private final ObjectReferenceDeque oldRootBuffer;
  private final RCZero zero;
  private final RCDecBuffer decBuffer0;
  private final RCDecBuffer decBuffer1;
  private RCDecBuffer decBuffer;
  private static volatile boolean performCycleCollection = false;

  /**
   * Constructor.
   */
  public RCBaseCollector() {
    newRootBuffer = new ObjectReferenceDeque("new-root", global().newRootPool);
    oldRootBuffer = new ObjectReferenceDeque("old-root", global().oldRootPool);
    modBuffer = new ObjectReferenceDeque("mod buf", global().modPool);
    backupTrace = new BTTraceLocal(global().backupTrace);
    zero = new RCZero();
    decBuffer0 = new RCDecBuffer(global().decPool0);
	decBuffer1 = new RCDecBuffer(global().decPool1);
  }
  
  /**
   * Get the root trace to use.
   */
  protected abstract TraceLocal getRootTrace();

  /****************************************************************************
   *
   * Collection
   */

  /** Perform garbage collection */
  public void collect() {
    Phase.beginNewPhaseStack(Phase.scheduleComplex(global().collection));
  }

  /**
   * Perform a per-collector collection phase.
   *
   * @param phaseId The collection phase to perform
   * @param primary perform any single-threaded local activities.
   */
  public void collectionPhase(short phaseId, boolean primary) {
    if (phaseId == RCBase.PREPARE) {
      getRootTrace().prepare();
      if (RCBase.CC_BACKUP_TRACE && RCBase.performCycleCollection) backupTrace.prepare();
      decBuffer = global().currentDecPool == 0 ? decBuffer0 : decBuffer1;
      performCycleCollection = RCBase.performCycleCollection;
      return;
    }

    if (phaseId == RCBase.CLOSURE) {
      getRootTrace().completeTrace();
      newRootBuffer.flushLocal();
      return;
    }

    if (phaseId == RCBase.BT_CLOSURE) {
      if (RCBase.CC_BACKUP_TRACE && RCBase.performCycleCollection) {
        backupTrace.completeTrace();
      }
      return;
    }

    if (phaseId == RCBase.PROCESS_OLDROOTBUFFER) {
      ObjectReference current;
      while(!(current = oldRootBuffer.pop()).isNull()) {
        decBuffer.push(current);
      }
      return;
    }

    if (phaseId == RCBase.PROCESS_NEWROOTBUFFER) {
      ObjectReference current;
      while(!(current = newRootBuffer.pop()).isNull()) {
        RCHeader.incRC(current);
        oldRootBuffer.push(current);
        if (RCBase.CC_BACKUP_TRACE && RCBase.performCycleCollection) {
          if (RCHeader.testAndMark(current)) {
            backupTrace.processNode(current);
          }
        }
      }
      oldRootBuffer.flushLocal();
      return;
    }

    if (phaseId == RCBase.PROCESS_MODBUFFER) {
      ObjectReference current;
      while(!(current = modBuffer.pop()).isNull()) {
        RCHeader.incRC(current);
      }
      return;
    }

    if (phaseId == RCBase.PROCESS_DECBUFFER) {
      if (performCycleCollection) {
          processDecBuf(decBuffer0);
          processDecBuf(decBuffer1);
      } else {
          decBuffer0.flushLocal();
          decBuffer1.flushLocal();
      }
      return;
    }

    if (phaseId == RCBase.RELEASE) {
      if (RCBase.CC_BACKUP_TRACE && RCBase.performCycleCollection) {
        backupTrace.release();
      }
      getRootTrace().release();
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(newRootBuffer.isEmpty());
        VM.assertions._assert(modBuffer.isEmpty());
      }
      return;
    }

    if (phaseId == RCBase.CONCURRENT_PREEMPT) {
		if (!performCycleCollection) {
			processDecBuf(decBuffer);
		}
		return;
	}

    super.collectionPhase(phaseId, primary);
  }

  /****************************************************************************
   *
   * Miscellaneous
   */

  /** @return The active global plan as an <code>RC</code> instance. */
  @Inline
  protected static RCBase global() {
    return (RCBase) VM.activePlan.global();
  }

  /** @return The current trace instance. */
  public final TraceLocal getCurrentTrace() {
    return getRootTrace();
  }

  @Override
  @Unpreemptible
  public void run() {
	  while (true) {
		  park();
		  if (Plan.concurrentWorkers.isMember(this)) {
			  concurrentCollect();
		  } else {
			  collect();
		  }
	  }
  }

  @Unpreemptible
  public void concurrentCollect() {
	if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!Plan.gcInProgress());
	short phaseId = Phase.getConcurrentPhaseId();
	concurrentCollectionPhase(phaseId);
  }

  @Unpreemptible
  public void concurrentCollectionPhase(short phaseId) {
	if (phaseId == RCBase.CONCURRENT) {
		if (VM.VERIFY_ASSERTIONS) {
			VM.assertions._assert(!Plan.gcInProgress());
		}
		if (!performCycleCollection) {
			decBuffer = global().currentDecPool == 0 ? decBuffer1 : decBuffer0;
			processDecBuf(decBuffer);
		}
		rendezvous();
		return;
	}
	Log.write("Concurrent phase ");
	Log.write(Phase.getName(phaseId));
	Log.writeln(" not handled.");
	VM.assertions.fail("Concurrent phase not handled!");
  }

  private void processDecBuf(RCDecBuffer decBuffer) {
	  ObjectReference current;
	  while (!(current = decBuffer.pop()).isNull()) {
		  if (RCHeader.decRC(current) == RCHeader.DEC_KILL) {
			  decBuffer.processChildren(current);
			  if (Space.isInSpace(RCBase.REF_COUNT, current)) {
				  RCBase.rcSpace.free(current);
			  } else if (Space.isInSpace(RCBase.REF_COUNT_LOS, current)) {
				  RCBase.rcloSpace.free(current);
			  } else if (Space.isInSpace(RCBase.IMMORTAL, current)) {
				  VM.scanning.scanObject(zero, current);
			  }
		  }
	  }
  }
}
