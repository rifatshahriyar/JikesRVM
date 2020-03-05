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

import static org.mmtk.utility.Constants.BITS_IN_ADDRESS;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class RCHeader {
  /* Requirements */
  public static final int LOCAL_GC_BITS_REQUIRED = 0;
  public static final int GLOBAL_GC_BITS_REQUIRED = 0;
  public static final int GC_HEADER_WORDS_REQUIRED = 1;

  /************************************************************************
   * RC header word
   */

  /* Header offset */
  public static final Offset RC_HEADER_OFFSET = VM.objectModel.GC_HEADER_OFFSET();

  /* Reserved to allow alignment hole filling to work */
  public static final int RESERVED_ALIGN_BIT = 0;

  /* The mark bit used for backup tracing. */
  public static final int MARK_BIT = 1;
  public static final Word MARK_BIT_MASK = Word.one().lsh(MARK_BIT);

  /* Current not using any bits for cycle detection, etc */
  public static final int BITS_USED = 2;

  /* Reference counting increments */
  public static final int INCREMENT_SHIFT = BITS_USED;
  public static final Word INCREMENT = Word.one().lsh(INCREMENT_SHIFT);
  public static final int AVAILABLE_BITS = BITS_IN_ADDRESS - BITS_USED;
  public static final Word INCREMENT_LIMIT = Word.one().lsh(BITS_IN_ADDRESS-1).not();
  public static final Word LIVE_THRESHOLD = INCREMENT;

  /* Return values from decRC */
  public static final int DEC_KILL = 0;
  public static final int DEC_ALIVE = 1;

  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  public static boolean isMarked(ObjectReference object) {
    return isHeaderMarked(object.toAddress().loadWord(RC_HEADER_OFFSET));
  }

  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  public static void clearMarked(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = object.toAddress().prepareWord(RC_HEADER_OFFSET);
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(isHeaderMarked(oldValue));
      newValue = oldValue.and(MARK_BIT_MASK.not());
    } while (!object.toAddress().attempt(oldValue, newValue, RC_HEADER_OFFSET));
    /*
    Word header = object.toAddress().loadWord(RC_HEADER_OFFSET);
    object.toAddress().store(header.and(MARK_BIT_MASK.not()), RC_HEADER_OFFSET);*/
  }

  /**
   * Has this object been marked by the most recent backup trace.
   */
  @Inline
  private static boolean isHeaderMarked(Word header) {
    return header.and(MARK_BIT_MASK).EQ(MARK_BIT_MASK);
  }

  /**
   * Attempt to atomically mark this object. Return true if the mark was performed.
   */
  @Inline
  public static boolean testAndMark(ObjectReference object) {
    Word oldValue, newValue;
    do {
      oldValue = object.toAddress().prepareWord(RC_HEADER_OFFSET);
      if (isHeaderMarked(oldValue)) {
        return false;
      }
      newValue = oldValue.or(MARK_BIT_MASK);
    } while (!object.toAddress().attempt(oldValue, newValue, RC_HEADER_OFFSET));
    return true;
  }

  /**
   * Perform any required initialization of the GC portion of the header.
   *
   * @param object the object
   * @param initialInc start with a reference count of 1 (0 if false)
   */
  @Inline
  public static void initializeHeader(ObjectReference object, boolean initialInc) {
    Word initialValue =  (initialInc) ? INCREMENT : Word.zero();
    object.toAddress().store(initialValue, RC_HEADER_OFFSET);
  }

  /**
   * Return true if given object is live
   *
   * @param object The object whose liveness is to be tested
   * @return True if the object is alive
   */
  @Inline
  @Uninterruptible
  public static boolean isLiveRC(ObjectReference object) {
    return object.toAddress().loadWord(RC_HEADER_OFFSET).GE(LIVE_THRESHOLD);
  }

  /**
   * Return the reference count for the object.
   *
   * @param object The object whose liveness is to be tested
   * @return True if the object is alive
   */
  @Inline
  @Uninterruptible
  public static int getRC(ObjectReference object) {
    return object.toAddress().loadWord(RC_HEADER_OFFSET).rshl(INCREMENT_SHIFT).toInt();
  }

  /**
   * Increment the reference count of an object.
   *
   * @param object The object whose reference count is to be incremented.
   */
  @Inline
  public static void incRC(ObjectReference object) {
    Word oldValue, newValue;
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(RCBase.isRCObject(object));
    do {
      oldValue = object.toAddress().prepareWord(RC_HEADER_OFFSET);
      newValue = oldValue.plus(INCREMENT);
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(newValue.LE(INCREMENT_LIMIT));
    } while (!object.toAddress().attempt(oldValue, newValue, RC_HEADER_OFFSET));
  }

  /**
   * Decrement the reference count of an object.  Return either
   * <code>DEC_KILL</code> if the count went to zero,
   * <code>DEC_ALIVE</code> if the count did not go to zero.
   *
   * @param object The object whose RC is to be decremented.
   * @return <code>DEC_KILL</code> if the count went to zero,
   * <code>DEC_ALIVE</code> if the count did not go to zero.
   */
  @Inline
  @Uninterruptible
  public static int decRC(ObjectReference object) {
    Word oldValue, newValue;
    int rtn;
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(RCBase.isRCObject(object));
      VM.assertions._assert(isLiveRC(object));
    }
    do {
      oldValue = object.toAddress().prepareWord(RC_HEADER_OFFSET);
      newValue = oldValue.minus(INCREMENT);
      if (newValue.LT(LIVE_THRESHOLD)) {
        rtn = DEC_KILL;
      } else {
        rtn = DEC_ALIVE;
      }
    } while (!object.toAddress().attempt(oldValue, newValue, RC_HEADER_OFFSET));
    return rtn;
  }
}
