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
package org.mmtk.plan.refcountnwb.backuptrace;

import org.mmtk.plan.refcountnwb.RCHeader;
import org.mmtk.policy.ExplicitLargeObjectSpace;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class implements the freeing of large objects during a backup trace.
 */
@Uninterruptible
public final class BTFreeLargeObjectSweeper extends ExplicitLargeObjectSpace.Sweeper {

  public boolean sweepLargeObject(ObjectReference object) {
    if (!RCHeader.isMarked(object)) {
      // Free the object
      return true;
    }
    // Clear the mark-bit and retain the object.
    RCHeader.clearMarked(object);
    return false;
  }
}
