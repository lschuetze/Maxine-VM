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
package com.sun.max.vm.cps.eir.amd64;

import com.sun.max.asm.amd64.*;
import com.sun.max.lang.*;
import com.sun.max.vm.collect.*;
import com.sun.max.vm.cps.eir.*;
import com.sun.max.vm.type.*;

/**
 * @author Bernd Mathiske
 */
public final class AMD64EirSafepoint extends EirSafepoint<EirInstructionVisitor, AMD64EirTargetEmitter> {

    public AMD64EirSafepoint(EirBlock block) {
        super(block);
    }

    @Override
    public void emit(AMD64EirTargetEmitter emitter) {
        emitter.addSafepoint(this);
        final AMD64EirRegister.General r = (AMD64EirRegister.General) emitter.abi().safepointLatchRegister();
        final AMD64GeneralRegister64 register = r.as64();
        emitter.assembler().mov(register, register.indirect());
    }

    @Override
    public void addFrameReferenceMap(WordWidth stackSlotWidth, ByteArrayBitMap map) {
        AMD64EirGenerator.addFrameReferenceMap(liveVariables(), stackSlotWidth, map);
    }

    @Override
    public void addRegisterReferenceMap(ByteArrayBitMap map) {
        for (EirVariable variable : liveVariables()) {
            if (variable.location().category() == EirLocationCategory.INTEGER_REGISTER) {
                final EirRegister register = (EirRegister) variable.location();
                if (variable.kind() == Kind.REFERENCE) {
                    map.set(register.ordinal);
                }
            }
        }
    }

}
