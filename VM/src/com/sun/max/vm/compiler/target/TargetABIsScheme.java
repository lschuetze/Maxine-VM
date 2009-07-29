/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.compiler.target;

import com.sun.max.util.*;
import com.sun.max.vm.*;

/**
 * @author Bernd Mathiske
 */
public abstract class TargetABIsScheme<IntegerRegister_Type extends Symbol, FloatingPointRegister_Type extends Symbol> extends AbstractVMScheme implements VMScheme {

    public boolean usingRegisterWindows() {
        return false;
    }

    private final TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> nativeABI;

    public TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> nativeABI() {
        return nativeABI;
    }

    private final TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> jitABI;

    public TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> jitABI() {
        return jitABI;
    }

    private final TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> optimizedJavaABI;

    public TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> optimizedJavaABI() {
        return optimizedJavaABI;
    }

    private final TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> interpreterABI;

    public TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> interpreterABI() {
        return interpreterABI;
    }

    protected TargetABIsScheme(VMConfiguration vmConfiguration,
                               TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> nativeABI,
                               TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> jitABI,
                               TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> optimizedJavaABI,
                               TargetABI<IntegerRegister_Type, FloatingPointRegister_Type> interpreterABI) {
        super(vmConfiguration);
        this.nativeABI = nativeABI;
        this.jitABI = jitABI;
        this.optimizedJavaABI = optimizedJavaABI;
        this.interpreterABI = interpreterABI;
    }
}
