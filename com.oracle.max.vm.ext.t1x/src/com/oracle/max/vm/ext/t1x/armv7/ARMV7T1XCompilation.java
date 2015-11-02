/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved. DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR
 * THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License version 2 for
 * more details (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version 2 along with this work; if not, write to
 * the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA or visit www.oracle.com if you need
 * additional information or have any questions.
 */
package com.oracle.max.vm.ext.t1x.armv7;

import com.oracle.max.asm.target.armv7.ARMV7;
import com.oracle.max.asm.target.armv7.ARMV7Assembler;
import com.oracle.max.asm.target.armv7.ARMV7Assembler.ConditionFlag;
import com.oracle.max.asm.target.armv7.ARMV7MacroAssembler;
import com.oracle.max.cri.intrinsics.MemoryBarriers;
import com.oracle.max.criutils.NativeCMethodinVM;
import com.oracle.max.vm.ext.maxri.MaxTargetMethod;
import com.oracle.max.vm.ext.t1x.*;
import com.sun.cri.bytecode.BytecodeLookupSwitch;
import com.sun.cri.bytecode.BytecodeTableSwitch;
import com.sun.cri.bytecode.Bytecodes;
import com.sun.cri.ci.CiAddress;
import com.sun.cri.ci.CiAddress.Scale;
import com.sun.cri.ci.CiKind;
import com.sun.cri.ci.CiRegister;
import com.sun.cri.ci.CiTargetMethod;
import com.sun.cri.ci.CiTargetMethod.JumpTable;
import com.sun.cri.ci.CiTargetMethod.LookupTable;
import com.sun.max.annotate.HOSTED_ONLY;
import com.sun.max.unsafe.Word;
import com.sun.max.vm.actor.holder.ClassActor;
import com.sun.max.vm.actor.member.ClassMethodActor;
import com.sun.max.vm.actor.member.FieldActor;
import com.sun.max.vm.actor.member.VirtualMethodActor;
import com.sun.max.vm.classfile.CodeAttribute;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.compiler.WordUtil;
import com.sun.max.vm.compiler.target.Adapter;
import com.sun.max.vm.compiler.target.Safepoints;
import com.sun.max.vm.runtime.FatalError;
import com.sun.max.vm.runtime.Trap;
import com.sun.max.vm.stack.JVMSFrameLayout;
import com.sun.max.vm.stack.armv7.ARMV7JVMSFrameLayout;
import com.sun.max.vm.thread.VmThread;
import com.sun.max.vm.type.Kind;
import com.sun.max.vm.type.SignatureDescriptor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oracle.max.asm.target.armv7.ARMV7.*;
import static com.oracle.max.vm.ext.t1x.T1X.dispFromCodeStart;
import static com.sun.max.platform.Platform.platform;
import static com.sun.max.platform.Platform.target;
import static com.sun.max.vm.classfile.ErrorContext.verifyError;
import static com.sun.max.vm.compiler.target.Safepoints.*;
import static com.sun.max.vm.stack.JVMSFrameLayout.JVMS_SLOT_SIZE;

//import java.io.PrintWriter;

public class ARMV7T1XCompilation extends T1XCompilation implements NativeCMethodinVM {


    public static AtomicInteger methodCounter = new AtomicInteger(536870912);
    public static int invokeDebugCounter = 0;
    private static final Object fileLock = new Object();
    private static File file;

    private static boolean DEBUG_METHODS = true;

    protected final ARMV7MacroAssembler asm;
    final PatchInfoARMV7 patchInfo;
    public static boolean FLOATDOUBLEREGISTERS = true;

    public ARMV7T1XCompilation(T1X compiler) {
        super(compiler);
        asm = new ARMV7MacroAssembler(target(), null);
        if (com.sun.max.vm.MaxineVM.isHosted() == false)
            // TO TURN SIMULATION ON ...
            if (com.oracle.max.asm.AbstractAssembler.SIMULATE_PLATFORM) {
                asm.maxineflush = this; // dirty hacky .... do it properly
                // END TO TURN SIMULATION ON
            } else {
                asm.maxineflush = null;
                // END TO TURN SIMULATION OFF
            }
        buf = asm.codeBuffer;
        patchInfo = new PatchInfoARMV7();
    }

    public void setDebug(boolean value) {
        DEBUG_METHODS = value;
    }

    static {
        initDebugMethods();
    }

    @Override
    public int maxine_instrumentationBuffer() {
        return com.sun.max.vm.compiler.target.arm.ARMTargetMethodUtil.maxine_instrumentationBuffer();
    }

    @Override
    public int maxine_flush_instrumentationBuffer() {
        // returns the int address of the method in C ... ugly but fast dynamic linking ....
        // should really do it the proper way by CriticalMethods etc etc
        return com.sun.max.vm.compiler.target.arm.ARMTargetMethodUtil.maxine_flush_instrumentationBuffer();
    }

    public static void initDebugMethods() {

        if ((file = new File(getDebugMethodsPath() + "debugT1Xmethods")).exists()) {
            file.delete();
        }
        file = new File(getDebugMethodsPath() + "debugT1Xmethods");
    }

    public static void writeDebugMethod(String name, int index) throws Exception {
        synchronized (fileLock) {
            try {
                assert DEBUG_METHODS;
                FileWriter fw = new FileWriter(file.getAbsoluteFile(), true);
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(index + " " + name + "\n");
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public static String getDebugMethodsPath() {
        return System.getenv("MAXINE_HOME") + "/maxine-tester/junit-tests/";

    }

    @Override
    protected void initFrame(ClassMethodActor method, CodeAttribute codeAttribute) {
        int maxLocals = codeAttribute.maxLocals;
        int maxStack = codeAttribute.maxStack;
        int maxParams = method.numberOfParameterSlots();
        if (method.isSynchronized() && !method.isStatic()) {
            synchronizedReceiver = maxLocals++;
        }
        frame = new ARMV7JVMSFrameLayout(maxLocals, maxStack, maxParams, T1XTargetMethod.templateSlots());
    }

    public ARMV7MacroAssembler getMacroAssembler() {
        return asm;
    }

    @Override
    public void decStack(int numberOfSlots) {
        assert numberOfSlots > 0;
        asm.addq(sp, numberOfSlots * JVMS_SLOT_SIZE);
    }

    @Override
    public void incStack(int numberOfSlots) {
        assert numberOfSlots > 0;
        asm.subq(sp, numberOfSlots * JVMS_SLOT_SIZE);
    }

    @Override
    protected void adjustReg(CiRegister reg, int delta) {
        asm.incrementl(reg, delta);
    }

    @Override
    public void peekObject(CiRegister dst, int index) {
        asm.setUpScratch(spWord(index));
        asm.ldr(ConditionFlag.Always, dst, asm.scratchRegister, 0);
    }

    @Override
    public void pokeObject(CiRegister src, int index) {
        asm.setUpScratch(spWord(index));
        asm.str(ConditionFlag.Always, src, asm.scratchRegister, 0);
    }

    @Override
    public void peekWord(CiRegister dst, int index) {
        asm.setUpScratch(spWord(index));
        asm.ldr(ConditionFlag.Always, dst, asm.scratchRegister, 0);

    }

    @Override
    public void pokeWord(CiRegister src, int index) {
        asm.setUpScratch(spWord(index));
        asm.str(ConditionFlag.Always, src, asm.scratchRegister, 0);
    }

    @Override
    public void peekInt(CiRegister dst, int index) {
        asm.setUpScratch(spInt(index));
        asm.ldr(ConditionFlag.Always, dst, asm.scratchRegister, 0);

    }

    @Override
    public void pokeInt(CiRegister src, int index) {
        asm.setUpScratch(spInt(index));
        asm.str(ConditionFlag.Always, src, asm.scratchRegister, 0);
    }

    @Override
    public void peekLong(CiRegister dst, int index) {
        assert dst.encoding < 10;
        asm.setUpScratch(spLong(index));
        //asm.sub(ConditionFlag.Always, false, scratch, scratch, 4, 0);
        asm.ldrd(ConditionFlag.Always, dst, scratch, 0); // dst needs to be big enough to hold a long!
    }

    @Override
    public void pokeLong(CiRegister src, int index) {
        assert src.encoding < 10;
        asm.setUpScratch(spLong(index));
        //asm.sub(ConditionFlag.Always, false, scratch, scratch, 4, 0);
        asm.strd(ARMV7Assembler.ConditionFlag.Always, src, scratch, 0); // put them on the stack on the stack!
    }

    @Override
    public void peekDouble(CiRegister dst, int index) {
        assert dst.isFpu();
        asm.setUpScratch(spLong(index));
        //asm.sub(ConditionFlag.Always, false, scratch, scratch, 4, 0);
        asm.vldr(ARMV7Assembler.ConditionFlag.Always, dst, scratch, 0, CiKind.Double, CiKind.Int);

    }

    @Override
    public void pokeDouble(CiRegister src, int index) {
        assert src.isFpu();
        asm.setUpScratch(spLong(index));
        //asm.sub(ConditionFlag.Always, false, scratch, scratch, 4, 0);
        asm.vstr(ARMV7Assembler.ConditionFlag.Always, src, scratch, 0, CiKind.Double, CiKind.Int);
    }

    @Override
    public void peekFloat(CiRegister dst, int index) {
        assert dst.isFpu();
        asm.setUpScratch(spInt(index));
        asm.vldr(ConditionFlag.Always, dst, asm.scratchRegister, 0, CiKind.Float, CiKind.Int);
    }

    @Override
    public void pokeFloat(CiRegister src, int index) {
        assert src.isFpu();
        asm.setUpScratch(spInt(index));
        asm.vstr(ConditionFlag.Always, src, asm.scratchRegister, 0, CiKind.Float, CiKind.Int);
    }

    @Override
    protected void assignObjectReg(CiRegister dst, CiRegister src) {
        asm.mov(ARMV7Assembler.ConditionFlag.Always, true, dst, src);
    }

    @Override
    protected void assignWordReg(CiRegister dst, CiRegister src) {
        asm.mov(ARMV7Assembler.ConditionFlag.Always, true, dst, src);
    }

    @Override
    protected void assignLong(CiRegister dst, long value) {
        assert dst.number < 10;
        asm.movw(ConditionFlag.Always, dst, (int) (value & 0xffff));
        asm.movt(ConditionFlag.Always, dst, (int) ((value >> 16) & 0xffff));
        asm.movw(ConditionFlag.Always, ARMV7.cpuRegisters[dst.encoding + 1], (int) (((value >> 32) & 0xffff)));
        asm.movt(ConditionFlag.Always, ARMV7.cpuRegisters[dst.encoding + 1], (int) (((value >> 48) & 0xffff)));
    }

    @Override
    protected void assignInvokeVirtualTemplateParameters(VirtualMethodActor virtualMethodActor, int receiverStackIndex) {
        //asm.insertForeverLoop();

            assignInt(0, "vTableIndex", virtualMethodActor.vTableIndex()  );
            peekObject(1, "receiver", receiverStackIndex);
        }
    @Override
    protected void do_invokestatic(int index) {
        ClassMethodRefConstant classMethodRef = cp.classMethodAt(index);
        Kind kind = invokeKind(classMethodRef.signature(cp));
        T1XTemplateTag tag = T1XTemplateTag.INVOKESTATICS.get(kind.asEnum);
        try {
            if (classMethodRef.isResolvableWithoutClassLoading(cp)) {
                com.sun.max.vm.actor.member.StaticMethodActor staticMethodActor = classMethodRef.resolveStatic(cp, index);
                if (processIntrinsic(staticMethodActor)) {
                    return;
                }
                if (staticMethodActor.holder().isInitialized()) {
                    do_invokestatic_resolved(tag, staticMethodActor);

                    int safepoint = callDirect();
                    finishCall(tag, kind, safepoint, staticMethodActor);
                    return;
                }
            }
        } catch (LinkageError error) {
            // Fall back on unresolved template that will cause the error to be rethrown at runtime.
        }
        start(tag);
        CiRegister target = template.sig.out.reg;
        //CiRegister target = ARMV7.r8;
        asm.mov32BitConstant(ARMV7.r8, 0xdead0001);
	asm.mov32BitConstant(ARMV7.r12, index);

        //asm.mov(ConditionFlag.Always, false, target, template.sig.out.reg);
        //asm.vmov(ConditionFlag.Always, ARMV7.s28, ARMV7.r0, null, CiKind.Float, CiKind.Int);

        assignObject(0, "guard", cp.makeResolutionGuard(index));
        finish();

        //asm.insertForeverLoop();
        target = ARMV7.r8;
        asm.mov(ConditionFlag.Always, false, target, ARMV7.r0);
	
        //EXTRAasm.mov(ConditionFlag.Always, false, ARMV7.r0, ARMV7.r1);
        //asm.vmov(ConditionFlag.Always, ARMV7.r0, ARMV7.s28, null, CiKind.Int, CiKind.Float);
        int safepoint = callIndirect(target, -1);
        asm.mov32BitConstant(ARMV7.r8, 0xdead0001);
        //asm.insertForeverLoop();

        finishCall(tag, kind, safepoint, null);
    }

    @Override
    protected void do_invokeinterface(int index) {
        InterfaceMethodRefConstant interfaceMethodRef = cp.interfaceMethodAt(index);
        SignatureDescriptor signature = interfaceMethodRef.signature(cp);

        if (interfaceMethodRef.holder(cp).toKind().isWord) {
            // Dynamic dispatch on Word types is not possible, since raw pointers do not have any method tables.
            do_invokespecial(index);
            return;
        }

        Kind kind = invokeKind(signature);
        T1XTemplateTag tag = T1XTemplateTag.INVOKEINTERFACES.get(kind.asEnum);
        int receiverStackIndex = receiverStackIndex(signature);
        try {
            if (interfaceMethodRef.isResolvableWithoutClassLoading(cp)) {
                try {
                    com.sun.max.vm.actor.member.MethodActor interfaceMethod = interfaceMethodRef.resolve(cp, index);
                    if (processIntrinsic(interfaceMethod)) {
                        return;
                    }
                    start(tag.resolved);
                    CiRegister target = template.sig.out.reg;
                    asm.mov32BitConstant(ARMV7.r8, 0xdead0002);
                    asm.mov32BitConstant(ARMV7.r12, index);


                    //System.out.println("INDEX " + index);
                    //System.out.println("RXERINDEX "+ receiverStackIndex);

                    assignObject(0, "methodActor", interfaceMethod);
                    peekObject(1, "receiver", receiverStackIndex);
		    asm.vmov(ConditionFlag.Always, ARMV7.s27, ARMV7.r1, null, CiKind.Float, CiKind.Int);
		    /// TRY savinf before we do the assign ...

                    target = ARMV7.r8;
                    finish();
                    asm.mov(ConditionFlag.Always, false, ARMV7.r8, ARMV7.r0);
                    asm.mov(ConditionFlag.Always, false, ARMV7.r0, ARMV7.r1);


                    int safepoint = callIndirect(target, receiverStackIndex);
                    asm.mov32BitConstant(ARMV7.r8, 0xdead0002);
                    finishCall(tag, kind, safepoint, null);
                    return;
                } catch (LinkageError e) {
                    // fall through
                }
            }
        } catch (LinkageError error) {
            // Fall back on unresolved template that will cause the error to be rethrown at runtime.
        }
        start(tag);
        CiRegister target = template.sig.out.reg;
        asm.mov32BitConstant(ARMV7.r8, 0xdead0003);
        asm.mov32BitConstant(ARMV7.r12, index);



        //System.out.println("ARMV7T1XCompilation resolv einterface class load");
        asm.vmov(ConditionFlag.Always, ARMV7.s27, ARMV7.r1, null, CiKind.Float, CiKind.Int);


        assignObject(0, "guard", cp.makeResolutionGuard(index));
        peekObject(1, "receiver", receiverStackIndex);
        finish();
        asm.mov(ConditionFlag.Always, false, ARMV7.r8, ARMV7.r0);
        asm.mov(ConditionFlag.Always, false, ARMV7.r0, ARMV7.r1);
        target = ARMV7.r8;

        int safepoint = callIndirect(target, receiverStackIndex);
        asm.mov32BitConstant(ARMV7.r8, 0xdead0003);
        finishCall(tag, kind, safepoint, null);
    }

    @Override
    protected void do_invokespecial(int index) {
        ClassMethodRefConstant classMethodRef = cp.classMethodAt(index);
        Kind kind = invokeKind(classMethodRef.signature(cp));
        SignatureDescriptor signature = classMethodRef.signature(cp);
        T1XTemplateTag tag = T1XTemplateTag.INVOKESPECIALS.get(kind.asEnum);
        int receiverStackIndex = receiverStackIndex(signature);
        try {
            if (classMethodRef.isResolvableWithoutClassLoading(cp)) {
                VirtualMethodActor virtualMethodActor = classMethodRef.resolveVirtual(cp, index);
                if (processIntrinsic(virtualMethodActor)) {
                    return;
                }
                do_invokespecial_resolved(tag, virtualMethodActor, receiverStackIndex);

                int safepoint = callDirect();
                finishCall(tag, kind, safepoint, virtualMethodActor);
                return;
            }
        } catch (LinkageError error) {
            // Fall back on unresolved template that will cause the error to be rethrown at runtime.
        }
        start(tag);
        CiRegister target = template.sig.out.reg;
        asm.mov32BitConstant(ARMV7.r8, 0xdead0004);
        asm.mov32BitConstant(ARMV7.r12, index);


        //System.out.println("INDEX " + index);
        //System.out.println("RXERINDEX "+ receiverStackIndex);


        assignObject(0, "guard", cp.makeResolutionGuard(index));
        peekObject(1, "receiver", receiverStackIndex);

        finish();
        asm.mov(ConditionFlag.Always, false, ARMV7.r8, ARMV7.r0);
        asm.mov(ConditionFlag.Always, false, ARMV7.r0, ARMV7.r1);
        target = ARMV7.r8;

        int safepoint = callIndirect(target, receiverStackIndex);
        asm.mov32BitConstant(ARMV7.r8, 0xdead0004);
        finishCall(tag, kind, safepoint, null);
    }

    @Override
    protected void do_invokespecial_resolved(T1XTemplateTag tag, VirtualMethodActor virtualMethodActor, int receiverStackIndex) {
        peekObject(ARMV7.r8, receiverStackIndex);
        int safepoint = nullCheck(ARMV7.r8);
        safepointsBuilder.addSafepoint(stream.currentBCI(), safepoint, null);
        asm.mov(ConditionFlag.Always, false, ARMV7.r12, ARMV7.r8);

    }

    @Override
    protected void do_invokevirtual(int index) {
        ClassMethodRefConstant classMethodRef = cp.classMethodAt(index);
        //System.out.println("CLASSMETHODREF " + classMethodRef);
        SignatureDescriptor signature = classMethodRef.signature(cp);

        if (classMethodRef.holder(cp).toKind().isWord) {
            // Dynamic dispatch on Word types is not possible, since raw pointers do not have any method tables.
            //System.out.println("do_invokevirtual special " + index);
            do_invokespecial(index);
            return;
        }

        Kind kind = invokeKind(signature);
        T1XTemplateTag tag = T1XTemplateTag.INVOKEVIRTUALS.get(kind.asEnum);
        int receiverStackIndex = receiverStackIndex(signature);
        //System.out.println("do_invokevirtual kind is " + kind);
        //System.out.println("do_invokevirtual RECEIVERSTAKCINDEX " + receiverStackIndex);
        try {
            if (classMethodRef.isResolvableWithoutClassLoading(cp)) {
                //System.out.println("do_invokevirtual resolvablewithoutclass loading");
                try {
                    VirtualMethodActor virtualMethodActor = classMethodRef.resolveVirtual(cp, index);
                    // System.out.println("do_invokevirtual vma " +virtualMethodActor + " index " + index);
                    if (processIntrinsic(virtualMethodActor)) {
                        //System.out.println("prcoessed intrinsic");
                        return;
                    }
                    if (virtualMethodActor.isPrivate() || virtualMethodActor.isFinal() || virtualMethodActor.holder().isFinal()) {
                        // this is an invokevirtual to a private or final method, treat it like invokespecial
                        //System.out.println("do_invokevirtual do_invokespecial_resolve");
                        do_invokespecial_resolved(tag, virtualMethodActor, receiverStackIndex);

                        int safepoint = callDirect();
                        finishCall(tag, kind, safepoint, virtualMethodActor);
                        return;
                    }
                    //System.out.println("do_invokevirtual emit an unprofiled virtual dispatch");
                    //System.out.println("INVOKEDEBUG " + invokeDebugCounter);
                    // emit an unprofiled virtual dispatch
                    start(tag.resolved);
                    CiRegister target = template.sig.out.reg;
                    //CiRegister target = ARMV7.r8;
                    asm.mov32BitConstant(ARMV7.r8, 0xdead0005);
                    asm.mov32BitConstant(ARMV7.r12, index);


                    //System.out.println("INDEX " + index);
                    //System.out.println("RXERINDEX "+ receiverStackIndex);
                    asm.vmov(ConditionFlag.Always, ARMV7.s29, ARMV7.r1, null, CiKind.Float, CiKind.Int);
		    asm.vmov(ConditionFlag.Always, ARMV7.s28, ARMV7.r0, null, CiKind.Float, CiKind.Int);

                    assignInvokeVirtualTemplateParameters(virtualMethodActor, receiverStackIndex);
                    asm.mov32BitConstant(ARMV7.r8, 0xdead0005);
                    finish();
                    asm.mov32BitConstant(ARMV7.r8, 0xdead0005);
                    target = ARMV7.r8;
                    asm.mov(ConditionFlag.Always, false, target, ARMV7.r0);

                    asm.mov(ConditionFlag.Always, false, ARMV7.r0, ARMV7.r1);
                    int safepoint = callIndirect(target, receiverStackIndex);
                    asm.mov32BitConstant(ARMV7.r8, 0xdead0005);
                    //System.out.println("SAFPOINT " + safepoint);
                    finishCall(tag, kind, safepoint, null);
                    return;
                } catch (LinkageError e) {
                    // fall through
                }
            }
        } catch (LinkageError error) {
            // Fall back on unresolved template that will cause the error to be rethrown at runtime.
        }
        //System.out.println("do_invokevirtual endcase");
        start(tag);
        CiRegister target = template.sig.out.reg;
        asm.mov32BitConstant(ARMV7.r8, 0xdead0006);
        asm.mov32BitConstant(ARMV7.r12, index);


        //System.out.println("INDEX " + index);
        //System.out.println("RXERINDEX " + receiverStackIndex);

        assignObject(0, "guard", cp.makeResolutionGuard(index));
        peekObject(1, "receiver", receiverStackIndex);
        asm.vmov(ConditionFlag.Always, ARMV7.s28, ARMV7.r1, null, CiKind.Float, CiKind.Int);
        finish();
        //System.out.println("DO CALL indirect");
        target = ARMV7.r8;
        asm.mov(ConditionFlag.Always, false, target, ARMV7.r0);
	asm.mov(ConditionFlag.Always, false, ARMV7.r0, ARMV7.r1);
        asm.vmov(ConditionFlag.Always, ARMV7.r1, ARMV7.s28, null, CiKind.Int, CiKind.Float);

        int safepoint = callIndirect(target, receiverStackIndex);
        asm.mov32BitConstant(ARMV7.r8, 0xdead0006);
        //System.out.println("DOING CALL INDIRECT");
        finishCall(tag, kind, safepoint, null);
        //System.out.println("DONE finishcall");
    }

    @Override
    protected void do_multianewarray(int index, int numberOfDimensions) {
        CiRegister lengths;
    /*
	X86-64 has a different return register to argument register set, but ARM as we have tried to follow AARPCS DOESNT
	so we need to save the r0 and restore it to r1
	Hoepfully with will work ... as long as the SP is not used for any arguments inbetween 
	*/
        {
            start(T1XTemplateTag.CREATE_MULTIANEWARRAY_DIMENSIONS);
            assignWordReg(0, "sp", sp);
            assignInt(1, "n", numberOfDimensions);
            lengths = template.sig.out.reg;
            finish();
            //decStack(numberOfDimensions);
            //asm.push(ConditionFlag.Always,1<<0);
            asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r0, null, CiKind.Float, CiKind.Int);


        }
        ClassConstant classRef = cp.classAt(index);
        if (classRef.isResolvableWithoutClassLoading(cp)) {
            start(T1XTemplateTag.MULTIANEWARRAY$resolved);
            ClassActor arrayClassActor = classRef.resolve(cp, index); // index+1? IF we pushed?
            assert arrayClassActor.isArrayClass();
            assert arrayClassActor.numberOfDimensions() >= numberOfDimensions : "dimensionality of array class constant smaller that dimension operand";
            assignObject(0, "arrayClassActor", arrayClassActor);
            //assignObjectReg(1, "lengths", lengths);

            //asm.pop(ConditionFlag.Always, 1 <<reg(1,"lengths",Kind.REFERENCE).encoding );
            asm.vmov(ConditionFlag.Always, reg(1, "lengths", Kind.REFERENCE), ARMV7.s31, null, CiKind.Int, CiKind.Float);

            finish();
        } else {
            // Unresolved case
            start(T1XTemplateTag.MULTIANEWARRAY);
            assignObject(0, "guard", cp.makeResolutionGuard(index)); //index+1 if we pushed?
            //assignObjectReg(1, "lengths", lengths);
            //asm.pop(ConditionFlag.Always,1<<1);
            //asm.pop(ConditionFlag.Always, 1 <<reg(1,"lengths",Kind.REFERENCE).encoding );
            asm.vmov(ConditionFlag.Always, reg(1, "lengths", Kind.REFERENCE), ARMV7.s31, null, CiKind.Int, CiKind.Float);
            finish();
        }
    }

    @Override
    protected void assignObject(CiRegister dst, Object value) {
        if (value == null) {
            asm.xorq(dst, dst);
            return;
        }

        int index = objectLiterals.size();
        objectLiterals.add(value);
        asm.nop(2);
        // leave space to do a setup scratch for a known address/value
        // it might needs to be bigger more nops required based on
        // how we fix up the address to be loaded into scratch.
        /*
         * APN Placeholder might be problematic.
         *
         * original code for method below if (value == null) { asm.xorq(dst, dst); return; }
         *
         * int index = objectLiterals.size(); objectLiterals.add(value);
         *
         * asm.movq(dst, CiAddress.Placeholder); int dispPos = buf.position() - 4; patchInfo.addObjectLiteral(dispPos,
         * index);
         */
        asm.addRegisters(ConditionFlag.Always, false, ARMV7.r12, ARMV7.r12, ARMV7.r15, 0, 0);
        int dispPos = buf.position() - 12; // three instructions
        asm.ldr(ConditionFlag.Always, dst, r12, 0);
        patchInfo.addObjectLiteral(dispPos, index);
    }

    @Override
    protected void loadInt(CiRegister dst, int index) {
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.INT)));
        asm.ldr(ConditionFlag.Always, dst, ARMV7.r12, 0);
    }

    @Override
    protected void loadLong(CiRegister dst, int index) {
        assert dst.number < 10; // to prevent screwing up scratch 2 registers required for double!
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.LONG)));
        //asm.sub(ConditionFlag.Always, false, scratch, scratch, 4, 0);
        asm.ldrd(ConditionFlag.Always, dst, scratch, 0);
    }

    @Override
    protected void loadWord(CiRegister dst, int index) {
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.WORD)));
        asm.ldr(ConditionFlag.Always, dst, ARMV7.r12, 0);
    }

    @Override
    protected void loadObject(CiRegister dst, int index) {
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.REFERENCE)));
        asm.ldr(ConditionFlag.Always, dst, ARMV7.r12, 0);
    }

    @Override
    protected void storeInt(CiRegister src, int index) {
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.INT)));
        asm.strImmediate(ConditionFlag.Always, 0, 0, 0, src, ARMV7.r12, 0);
    }

    @Override
    protected void storeLong(CiRegister src, int index) {
        assert src.number < 10; // sanity checking longs must not screw up scratch
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.LONG)));
        //asm.sub(ConditionFlag.Always, false, scratch, scratch, 4, 0);
        asm.strd(ARMV7Assembler.ConditionFlag.Always, src, scratch, 0);
    }

    @Override
    protected void storeWord(CiRegister src, int index) {
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.WORD)));
        asm.strImmediate(ConditionFlag.Always, 0, 0, 0, src, asm.scratchRegister, 0);
    }

    @Override
    protected void storeObject(CiRegister src, int index) {
        asm.setUpScratch(localSlot(localSlotOffset(index, Kind.REFERENCE)));
        asm.str(ARMV7Assembler.ConditionFlag.Always, src, scratch, 0);
    }

    @Override
    public void assignInt(CiRegister dst, int value) {
        asm.mov32BitConstant(dst, value);
    }

    @Override
    protected void assignFloat(CiRegister dst, float value) {
        assert dst.number >= ARMV7.s0.number && dst.number <= ARMV7.s31.number;
        asm.mov32BitConstant(ARMV7.r12, Float.floatToRawIntBits(value));
        asm.vmov(ConditionFlag.Always, dst, ARMV7.r12, null, CiKind.Float, CiKind.Int);
    }

    @Override
    protected void do_store(int index, Kind kind) {
        // TODO ensure that r8 and r9 are not allocatable
        switch (kind.asEnum) {
            case INT:
            case FLOAT:
                peekInt(ARMV7.r8, 0);
                decStack(1);
                storeInt(ARMV7.r8, index);
                break;
            case REFERENCE:
                peekWord(ARMV7.r8, 0);
                decStack(1);
                storeWord(ARMV7.r8, index);
                break;
            case LONG:
            case DOUBLE:
                // TODO potential corruption of r9 need to use floatreg as stackoperation in progress
                // cnanot push r9 to stack
                asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r9, null, CiKind.Float, CiKind.Int);
                peekLong(ARMV7.r8, 0);
                decStack(2);
                storeLong(ARMV7.r8, index);
                asm.vmov(ConditionFlag.Always, ARMV7.r9, ARMV7.s31, null, CiKind.Int, CiKind.Float);
                break;
            default:
                throw new InternalError("Unexpected kind: " + kind);
        }
    }

    @Override
    protected void do_load(int index, Kind kind) {
        // TODO ensure that r8 and r9 are not allocatable
        switch (kind.asEnum) {
            case INT:
            case FLOAT:
                loadInt(ARMV7.r8, index); // uses FP not stack!
                incStack(1);
                pokeInt(ARMV7.r8, 0); // was slot zero
                break;
            case REFERENCE:
                loadWord(ARMV7.r8, index); // uses FP not stack
                incStack(1);
                pokeWord(ARMV7.r8, 0);
                break;
            case LONG:
            case DOUBLE:
                // TODO potential corruption of r9
                // TODO potential corruption of r9 need to use floatreg as stackoperation in progress
                // cnanot push r9 to stack
                asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r9, null, CiKind.Float, CiKind.Int);
                loadLong(ARMV7.r8, index); // uses FP not stack
                incStack(2);
                pokeLong(ARMV7.r8, 0);
                asm.vmov(ConditionFlag.Always, ARMV7.r9, ARMV7.s31, null, CiKind.Int, CiKind.Float);
                break;
            default:
                throw new InternalError("Unexpected kind: " + kind);
        }
    }

    @Override
    protected void do_oconst(Object value) {
        assignObject(ARMV7.r8, value);
        incStack(1);
        pokeObject(ARMV7.r8, 0);
    }

    @Override
    protected void do_iconst(int value) {
        assignInt(ARMV7.r8, value);
        incStack(1);
        pokeInt(ARMV7.r8, 0);
    }

    @Override
    protected void do_iinc(int index, int increment) {
        loadInt(ARMV7.r8, index);
        adjustReg(ARMV7.r8, increment);
        storeInt(ARMV7.r8, index);
    }

    @Override
    protected void do_fconst(float value) {
        assignInt(ARMV7.r8, Float.floatToRawIntBits(value));
        incStack(1);
        pokeInt(ARMV7.r8, 0);
    }

    @Override
    protected void do_dconst(double value) {
        // potential corruption of r9
        asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r9, null, CiKind.Float, CiKind.Int);
        assignLong(ARMV7.r8, Double.doubleToRawLongBits(value));
        incStack(2);
        pokeLong(ARMV7.r8, 0);
        asm.vmov(ConditionFlag.Always, ARMV7.r9, ARMV7.s31, null, CiKind.Int, CiKind.Float);

    }

    @Override
    protected void do_lconst(long value) {
        // potential corruption of r9
        asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r9, null, CiKind.Float, CiKind.Int);
        assignLong(ARMV7.r8, value);
        incStack(2);
        pokeLong(ARMV7.r8, 0);
        asm.vmov(ConditionFlag.Always, ARMV7.r9, ARMV7.s31, null, CiKind.Int, CiKind.Float);

    }

    @Override
    protected void assignDouble(CiRegister dst, double value) {
        assert dst.isFpu();
        // potential corruption of r9
        asm.push(ConditionFlag.Always, 1 << 9);
        assignLong(ARMV7.r8, Double.doubleToRawLongBits(value));
        asm.vmov(ConditionFlag.Always, dst, ARMV7.r8, ARMV7.r9, CiKind.Double, CiKind.Int);
        asm.pop(ConditionFlag.Always, 1 << 9);
    }

    @Override
    protected int callDirect() {
        // alignDirectCall(buf.position()); NOT required for ARM APN believes.
        int causePos = buf.position();
        asm.call();
        int safepointPos = buf.position();
        asm.nop(); // nop separates any potential safepoint emitted as a successor to the call
        return Safepoints.make(safepointPos, causePos, DIRECT_CALL, TEMPLATE_CALL);
    }

    @Override
    protected int callIndirect(CiRegister target, int receiverStackIndex) {
        if (target != ARMV7.r8) {
            asm.mov(ConditionFlag.Always, false, ARMV7.r8, target); // ctry to fix the problem
            target = ARMV7.r8;

        }
        if (receiverStackIndex >= 0) {

            //peekObject(ARMV7.r1, receiverStackIndex); // was rdi?
            peekObject(ARMV7.r0, receiverStackIndex);
        }
        //System.out.println("callIndirect TARGET REG is " +target.encoding);
        if (target.encoding != ARMV7.r8.encoding) {
            asm.xorq(ARMV7.r8, ARMV7.r8);
        }
        // make r8 the target
        int causePos = buf.position();
        //System.out.println("CAUSEPOS " + causePos);
        asm.call(target);
        int safepointPos = buf.position();
        asm.nop(); // nop separates any potential safepoint emitted as a successor to the call
        return Safepoints.make(safepointPos, causePos, INDIRECT_CALL, TEMPLATE_CALL);
    }

    @Override
    protected int nullCheck(CiRegister src) {
        // nullCheck on AMD64 testl(AMD64.rax, new CiAddress(Word, r.asValue(Word), 0));
        int safepointPos = buf.position();
        asm.nullCheck(src);
        return Safepoints.make(safepointPos);

    }

    private void alignDirectCall(int callPos) {
        // Align bytecode call site for MT safe patching
        // TODO APN is this required at all for ARMv7?
        final int alignment = 7;
        final int roundDownMask = ~alignment;
        // final int directCallInstructionLength = 5; // [0xE8] disp32
        final int directCallInstructionLength = 4; // BL on ARM
        final int endOfCallSite = callPos + (directCallInstructionLength - 1);
        if ((callPos & roundDownMask) != (endOfCallSite & roundDownMask)) {
            // Emit nops to align up to next 8-byte boundary
            asm.nop(8 - (callPos & alignment));
        }
    }

    private int framePointerAdjustment() {
        // TODO APN this is required for ARMv7 but it is incorrect at the moment with fakedFrame
        final int enterSize = frame.frameSize() - Word.size();// Whe we push we adjust the stack ptr - Word.size();
        return enterSize - frame.sizeOfNonParameterLocals();
    }

    @Override
    protected Adapter emitPrologue() {
        Adapter adapter = null;
        if (adapterGenerator != null) {
            adapter = adapterGenerator.adapt(method, asm);
        }
        // stacksize = imm16
        // push frame pointer
        // framepointer = stackpointer
        // stackptr = framepointer -stacksize

        int frameSize = frame.frameSize();
        asm.push(ConditionFlag.Always, 1 << 14); // push return address on stack
        asm.push(ConditionFlag.Always, 1 << 11); // push frame pointer onto STACK
        asm.mov(ConditionFlag.Always, false, ARMV7.r11, ARMV7.r13); // create a new framepointer = stack ptr
        asm.subq(ARMV7.r13, frameSize - Word.size()); // APN is this necessary for  ARM ie push does it anyway?
        asm.subq(ARMV7.r11, framePointerAdjustment()); // TODO FP/SP not being set up correctly ...

        //TODO: Fix below
        if (Trap.STACK_BANGING) {
            int pageSize = platform().pageSize;
            int framePages = frameSize / pageSize;
            // emit multiple stack bangs for methods with frames larger than a page
            for (int i = 0; i <= framePages; i++) {
                int offset = (i + VmThread.STACK_SHADOW_PAGES) * pageSize;
                // Deduct 'frameSize' to handle frames larger than (VmThread.STACK_SHADOW_PAGES * pageSize)
                offset = offset - frameSize;
                // RSP is r13!
                asm.setUpScratch(new CiAddress(WordUtil.archKind(), RSP, -offset));
                asm.strImmediate(ConditionFlag.Always, 0, 0, 0, ARMV7.r0, asm.scratchRegister, 0); // was LR
                // APN guessing rax is return address.
                // asm.movq(new CiAddress(WordUtil.archKind(), RSP, -offset), rax);
            }
        }
        if (DEBUG_METHODS) {
            int a = methodCounter.incrementAndGet();
            asm.mov32BitConstant(ARMV7.r12, a);
            try {
                writeDebugMethod(method.holder() + "." + method.name(), a);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return adapter;
    }

    @Override
    protected void emitUnprotectMethod() {
        protectionLiteralIndex = objectLiterals.size();
        objectLiterals.add(T1XTargetMethod.PROTECTED);
        asm.xorq(ARMV7.r8, ARMV7.r8);
        //System.out.println("emitUnProtect partially commented out ... OBJECT LITERALS");
        //asm.setUpScratch(CiAddress.Placeholder);
        //asm.str(ConditionFlag.Always,ARMV7.r8,scratch,0);
        asm.nop(2);

        int dispPos = buf.position() - 8;
        patchInfo.addObjectLiteral(dispPos, protectionLiteralIndex);
        asm.addRegisters(ConditionFlag.Always, false, ARMV7.r12, ARMV7.r12, ARMV7.r15, 0, 0);
        asm.strImmediate(ConditionFlag.Always, 0, 0, 0, ARMV7.r8, ARMV7.r12, 0);

    }

    @Override
    protected void emitEpilogue() {
        asm.addq(ARMV7.r11, framePointerAdjustment()); // we might be missing some kind of pop here?
        asm.mov(ConditionFlag.Always, true, ARMV7.r13, ARMV7.r11);
        final short stackAmountInBytes = (short) frame.sizeOfParameters();
        // ALTERED MOVED POP to before RET
        //asm.mov32BitConstant(scratch, stackAmountInBytes);
        //asm.addRegisters(ConditionFlag.Always, true, ARMV7.r13, ARMV7.r13, ARMV7.r12, 0, 0);
        asm.pop(ConditionFlag.Always, 1 << 11); // POP the frame pointer
        asm.pop(ConditionFlag.Always, 1 << 8); // POP return address into r8
        asm.mov32BitConstant(scratch, stackAmountInBytes);
        asm.addRegisters(ConditionFlag.Always, true, ARMV7.r13, ARMV7.r13, ARMV7.r12, 0, 0);
        asm.mov(ConditionFlag.Always, false, ARMV7.r15, ARMV7.r8); // RETURN


        //asm.ret(); // mov R14 to r15 ,,, who restores the rest oI//f the environment?
    }

    @Override
    protected void do_preVolatileFieldAccess(T1XTemplateTag tag, FieldActor fieldActor) {
        if (fieldActor.isVolatile()) {
            boolean isWrite = tag.opcode == Bytecodes.PUTFIELD || tag.opcode == Bytecodes.PUTSTATIC;
            asm.membar(isWrite ? MemoryBarriers.JMM_PRE_VOLATILE_WRITE : MemoryBarriers.JMM_PRE_VOLATILE_READ);
        }
    }

    @Override
    protected void do_postVolatileFieldAccess(T1XTemplateTag tag, FieldActor fieldActor) {
        if (fieldActor.isVolatile()) {
            boolean isWrite = tag.opcode == Bytecodes.PUTFIELD || tag.opcode == Bytecodes.PUTSTATIC;
            asm.membar(isWrite ? MemoryBarriers.JMM_POST_VOLATILE_WRITE : MemoryBarriers.JMM_POST_VOLATILE_READ);
        }
    }

    @Override
    protected void do_tableswitch() {
        int bci = stream.currentBCI();
        BytecodeTableSwitch ts = new BytecodeTableSwitch(stream, bci);
        int lowMatch = ts.lowKey();
        int highMatch = ts.highKey();
        if (lowMatch > highMatch) {
            throw verifyError("Low must be less than or equal to high in TABLESWITCH");
        }

        // Pop index from stack into scratch
        asm.setUpScratch(new CiAddress(CiKind.Int, RSP));
        asm.ldr(ConditionFlag.Always, ARMV7.r8, asm.scratchRegister, 0);
        asm.addq(r13, JVMSFrameLayout.JVMS_SLOT_SIZE);
        asm.push(ConditionFlag.Always, 1 << 10 | 1 << 9);
        asm.mov(ConditionFlag.Always, false, ARMV7.r9, ARMV7.r8); // r9 stores index

        // Jump to default target if index is not within the jump table
        startBlock(ts.defaultTarget());
        asm.cmpl(r9, lowMatch);
        int pos = buf.position();
        patchInfo.addJCC(ConditionFlag.SignedLesser, pos, ts.defaultTarget());
        asm.jcc(ConditionFlag.SignedLesser, 0, true);
        if (lowMatch != 0) {
            asm.subq(r9, lowMatch);
            asm.cmpl(r9, highMatch - lowMatch);
        } else {
            asm.cmpl(r9, highMatch);
        }
        pos = buf.position();
        patchInfo.addJCC(ConditionFlag.SignedGreater, pos, ts.defaultTarget());
        asm.jcc(ConditionFlag.SignedGreater, 0, true);

        // Set r9 to address of jump table
        int leaPos = buf.position();
        asm.leaq(r7, CiAddress.Placeholder);
        int afterLea = buf.position();

        // Load jump table entry into r15 and jump to it
        asm.setUpScratch(new CiAddress(CiKind.Int, r7.asValue(), r9.asValue(), Scale.Times4, 0));
        asm.ldr(ConditionFlag.Always, r12, ARMV7.r12, 0);
        asm.addRegisters(ConditionFlag.Always, false, r12, ARMV7.r15, r12, 0, 0); // need to be careful are we using the right add!
        asm.add(ConditionFlag.Always, false, r12, r12, 8, 0);
        asm.mov(ConditionFlag.Always, false, ARMV7.r15, ARMV7.r12);
        asm.pop(ConditionFlag.Always, 1 << 9 | 1 << 10); // restore r9/r10

        // Inserting padding so that jump table address is 4-byte aligned
        if ((buf.position() & 0x3) != 0) {
            asm.nop(4 - (buf.position() & 0x3));
        }

        // Patch LEA instruction above now that we know the position of the jump table
        int jumpTablePos = buf.position();
        buf.setPosition(leaPos); // move the asm buffer position to where the leaq was added
        asm.leaq(r7, new CiAddress(WordUtil.archKind(), rip.asValue(), jumpTablePos - afterLea)); // patch it
        buf.setPosition(jumpTablePos); // reposition back to the correct place

        // Emit jump table entries
        for (int i = 0; i < ts.numberOfCases(); i++) {
            int targetBCI = ts.targetAt(i);
            startBlock(targetBCI);
            pos = buf.position();
            patchInfo.addJumpTableEntry(pos, jumpTablePos, targetBCI);
            buf.emitInt(0);
        }
        if (codeAnnotations == null) {
            codeAnnotations = new ArrayList<CiTargetMethod.CodeAnnotation>();
        }
        codeAnnotations.add(new JumpTable(jumpTablePos, ts.lowKey(), ts.highKey(), 4));
    }

    @Override
    protected void do_lookupswitch() {
        int bci = stream.currentBCI();
        BytecodeLookupSwitch ls = new BytecodeLookupSwitch(stream, bci);
        if (ls.numberOfCases() == 0) {
            // Pop the key
            decStack(1);
            int targetBCI = ls.defaultTarget();
            startBlock(targetBCI);
            if (stream.nextBCI() == targetBCI) {
                // Skip completely if default target is next instruction
            } else if (targetBCI <= bci) {
                int target = bciToPos[targetBCI];
                assert target != 0;
                asm.jmp(target, false);
            } else {
                patchInfo.addJMP(buf.position(), targetBCI);
                asm.jmp(0, true);
            }
        } else {
            asm.setUpScratch(new CiAddress(CiKind.Int, RSP));
            asm.ldr(ConditionFlag.Always, ARMV7.r8, asm.scratchRegister, 0);
            asm.addq(ARMV7.r13, JVMSFrameLayout.JVMS_SLOT_SIZE);
            asm.push(ConditionFlag.Always, 1 << 7 | 1 << 9 | 1 << 10);
            asm.mov(ConditionFlag.Always, false, r9, r8); // r9 stores index

            // Set r10 to address of lookup table
            int leaPos = buf.position();
            asm.leaq(r6, CiAddress.Placeholder);
            int afterLea = buf.position();

            // Initialize r7 to index of last entry
            asm.mov32BitConstant(r7, (ls.numberOfCases() - 1) * 2);

            int loopPos = buf.position();

            // Compare the value against the key
            asm.setUpScratch(new CiAddress(CiKind.Int, r6.asValue(), r7.asValue(), Scale.Times4, 0));
            asm.ldr(ConditionFlag.Always, r12, r12, 0);
            asm.cmpl(ARMV7.r9, ARMV7.r12);

            // If equal, exit loop
            int matchTestPos = buf.position();
            final int placeholderForShortJumpDisp = matchTestPos + 4;
            asm.jcc(ConditionFlag.Equal, placeholderForShortJumpDisp, false);
            assert buf.position() - matchTestPos == 4;

            // Decrement loop var and jump to top of loop if it did not go below zero (i.e. carry flag was not set)
            asm.sub(ConditionFlag.Always, true, r7, r7, 2, 0);
            asm.jcc(ConditionFlag.Positive, loopPos, false);

            // Jump to default target
            startBlock(ls.defaultTarget());
            patchInfo.addJMP(buf.position(), ls.defaultTarget());
            asm.pop(ConditionFlag.Always, 1 << 9 | 1 << 10 | 1 << 7);
            asm.jmp(0, true);

            // Patch the first conditional branch instruction above now that we know where's it's going
            int matchPos = buf.position();
            buf.setPosition(matchTestPos);
            asm.jcc(ConditionFlag.Equal, matchPos, false);
            buf.setPosition(matchPos);

            // Load jump table entry into r15 and jump to it
            asm.setUpScratch(new CiAddress(CiKind.Int, r6.asValue(), r7.asValue(), Scale.Times4, 4));
            asm.ldr(ConditionFlag.Always, r12, r12, 0);
            asm.addRegisters(ConditionFlag.Always, false, r12, r15, r12, 0, 0);
            asm.add(ConditionFlag.Always, false, r12, r12, 8, 0);
            asm.mov(ConditionFlag.Always, true, r15, r12);
            asm.pop(ConditionFlag.Always, 1 << 9 | 1 << 10 | 1 << 7);

            // Inserting padding so that lookup table address is 4-byte aligned
            while ((buf.position() & 0x3) != 0) {
                asm.nop();
            }

            // Patch the LEA instruction above now that we know the position of the lookup table
            int lookupTablePos = buf.position();
            buf.setPosition(leaPos);
            asm.leaq(r6, new CiAddress(WordUtil.archKind(), rip.asValue(), (lookupTablePos - afterLea)));
            buf.setPosition(lookupTablePos);

            // Emit lookup table entries
            for (int i = 0; i < ls.numberOfCases(); i++) {
                int key = ls.keyAt(i);
                int targetBCI = ls.targetAt(i);
                startBlock(targetBCI);
                patchInfo.addLookupTableEntry(buf.position(), key, lookupTablePos, targetBCI);
                buf.emitInt(key);
                buf.emitInt(0);
            }
            if (codeAnnotations == null) {
                codeAnnotations = new ArrayList<CiTargetMethod.CodeAnnotation>();
            }
            codeAnnotations.add(new LookupTable(lookupTablePos, ls.numberOfCases(), 4, 4));
        }
    }

    @Override
    public void cleanup() {
        patchInfo.size = 0;
        super.cleanup();
    }

    @Override
    protected void branch(int opcode, int targetBCI, int bci) {
        ConditionFlag cc;
        // Important: the compare instructions must come after the stack
        // adjustment instructions as both affect the condition flags.
        asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r9, null, CiKind.Float, CiKind.Int);

        switch (opcode) {
            case Bytecodes.IFEQ:
                peekInt(r8, 0);
                assignInt(r9, 0);
                decStack(1);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.equal;
                cc = ConditionFlag.Equal;
                break;
            case Bytecodes.IFNE:
                peekInt(r8, 0);
                assignInt(r9, 0);
                decStack(1);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.notEqual;
                cc = ConditionFlag.NotEqual;
                break;
            case Bytecodes.IFLE:
                peekInt(r8, 0);
                assignInt(r9, 0);
                decStack(1);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.lessEqual;
                cc = ConditionFlag.SignedLowerOrEqual;
                break;
            case Bytecodes.IFLT:
                peekInt(r8, 0);
                assignInt(r9, 0);
                decStack(1);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.less;
                cc = ConditionFlag.SignedLesser;
                break;
            case Bytecodes.IFGE:
                peekInt(r8, 0);
                assignInt(r9, 0);
                decStack(1);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.greaterEqual;
                cc = ConditionFlag.SignedGreaterOrEqual;
                break;
            case Bytecodes.IFGT:
                peekInt(r8, 0);
                assignInt(r9, 0);
                decStack(1);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.greater;
                cc = ConditionFlag.SignedGreater;
                break;
            case Bytecodes.IF_ICMPEQ:
                peekInt(r8, 1);
                peekInt(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.equal;
                cc = ConditionFlag.Equal;
                break;
            case Bytecodes.IF_ICMPNE:
                peekInt(r8, 1);
                peekInt(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.NotEqual;
                // cc = ConditionFlag.notEqual;
                break;
            case Bytecodes.IF_ICMPGE:
                peekInt(r8, 1);
                peekInt(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.SignedGreaterOrEqual;
                // cc = ConditionFlag.greaterEqual;
                break;
            case Bytecodes.IF_ICMPGT:
                peekInt(r8, 1);
                peekInt(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.greater;
                cc = ConditionFlag.SignedGreater;
                break;
            case Bytecodes.IF_ICMPLE:
                peekInt(r8, 1);
                peekInt(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.SignedLowerOrEqual;
                // cc = ConditionFlag.lessEqual;
                break;
            case Bytecodes.IF_ICMPLT:
                peekInt(r8, 1);
                peekInt(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                // cc = ConditionFlag.less;
                cc = ConditionFlag.SignedLesser;
                break;
            case Bytecodes.IF_ACMPEQ:
                peekObject(r8, 1);
                peekObject(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.Equal;
                // cc = ConditionFlag.equal;
                break;
            case Bytecodes.IF_ACMPNE:
                peekObject(r8, 1);
                peekObject(r9, 0);
                decStack(2);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.NotEqual;
                // cc= ConditionFlag.notEqual;
                break;
            case Bytecodes.IFNULL:
                peekObject(r8, 0);
                assignObject(r9, null);
                decStack(1);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.Equal;
                // cc = ConditionFlag.equal;
                break;
            case Bytecodes.IFNONNULL:
                peekObject(r9, 0);
                assignObject(r9, null);
                decStack(1);
                asm.cmpl(r8, r9);
                cc = ConditionFlag.NotEqual;
                // cc = ConditionFlag.notEqual;
                break;
            case Bytecodes.GOTO:
            case Bytecodes.GOTO_W:
                cc = null;
                break;
            default:
                throw new InternalError("Unknown branch opcode: " + Bytecodes.nameOf(opcode));

        }
        asm.vmov(ConditionFlag.Always, ARMV7.r9, ARMV7.s31, null, CiKind.Int, CiKind.Float);


        int pos = buf.position();
        if (bci < targetBCI) {
            // Forward branch
            if (cc != null) {
                patchInfo.addJCC(cc, pos, targetBCI);
                asm.jcc(cc, 0, true);
            } else {
                // Unconditional jump
                patchInfo.addJMP(pos, targetBCI);
                asm.jmp(0, true);
            }
            assert bciToPos[targetBCI] == 0;
        } else {
            // Backward branch

            // Compute relative offset.
            final int target = bciToPos[targetBCI];
            if (cc == null) {
                asm.jmp(target, false);
            } else {
                asm.jcc(cc, target, false);
            }
        }
    }

    @Override
    protected void addObjectLiteralPatch(int index, int patchPos) {
        final int dispPos = patchPos;
        patchInfo.addObjectLiteral(dispPos, index);
    }

    @Override
    protected void fixup() {
        int i = 0;
        int[] data = patchInfo.data;
        while (i < patchInfo.size) {
            int tag = data[i++];
            if (tag == PatchInfoARMV7.JCC) {
                ConditionFlag cc = ConditionFlag.values[data[i++]];
                int pos = data[i++];
                int targetBCI = data[i++];
                int target = bciToPos[targetBCI];
                assert target != 0;
                buf.setPosition(pos);
                asm.jcc(cc, target, true);
            } else if (tag == PatchInfoARMV7.JMP) {
                int pos = data[i++];
                int targetBCI = data[i++];
                int target = bciToPos[targetBCI];
                assert target != 0;
                buf.setPosition(pos);
                asm.jmp(target, true);
            } else if (tag == PatchInfoARMV7.JUMP_TABLE_ENTRY) {
                int pos = data[i++];
                int jumpTablePos = data[i++];
                int targetBCI = data[i++];
                int target = bciToPos[targetBCI];
                assert target != 0;
                int disp = target - jumpTablePos;
                buf.setPosition(pos);
                buf.emitInt(disp);
            } else if (tag == PatchInfoARMV7.LOOKUP_TABLE_ENTRY) {
                int pos = data[i++];
                int key = data[i++];
                int lookupTablePos = data[i++];
                int targetBCI = data[i++];
                int target = bciToPos[targetBCI];
                assert target != 0;
                int disp = target - lookupTablePos;
                buf.setPosition(pos);
                buf.emitInt(key);
                buf.emitInt(disp);
            } else if (tag == PatchInfoARMV7.OBJECT_LITERAL) {
                int dispPos = data[i++];
                int index = data[i++];
                int dummyDispPos = dispPos + 12; // +8 for PC and +8 for position of PC relative ADD
                assert objectLiterals.get(index) != null;
                buf.setPosition(dispPos);
                int dispFromCodeStart = dispFromCodeStart(objectLiterals.size(), 0, index, true);
                //int disp = movqDisp(dispPos, dispFromCodeStart);
                int disp = movqDisp(dummyDispPos, dispFromCodeStart);
                buf.setPosition(dispPos);
                // store  the value in r8 at the PC+ disp.(done at the patch insertion!!!! NOT HERE see emitUnProtectMethod)
                int val = asm.movwHelper(ConditionFlag.Always, ARMV7.r12, disp & 0xffff);
                buf.emitInt(val);
                val = asm.movtHelper(ConditionFlag.Always, ARMV7.r12, (disp >> 16) & 0xffff);
                buf.emitInt(val);

            } else {
                throw FatalError.unexpected(String.valueOf(tag));
            }
        }
    }

    public static int movqDisp(int dispPos, int dispFromCodeStart) {
        assert dispFromCodeStart < 0;
        final int dispSize = 4;
        return dispFromCodeStart - dispPos - dispSize;
    }

    @HOSTED_ONLY
    public static int[] findDataPatchPosns(MaxTargetMethod source, int dispFromCodeStart) {
        int[] result = {};
        for (int pos = 0; pos < source.codeLength(); pos++) {
            for (CiRegister reg : ARMV7.cpuRegisters) {
                final int extraOffset = 12;
                // Compute displacement operand position for a movq at 'pos'
                ARMV7Assembler asm = new ARMV7Assembler(target(), null);
                asm.setUpScratch(CiAddress.Placeholder);
                asm.addRegisters(ConditionFlag.Always, false, ARMV7.r12, ARMV7.r12, ARMV7.r15, 0, 0);
                asm.ldr(ConditionFlag.Always, reg, r12, 0);
                int dispPos = pos + asm.codeBuffer.position() - extraOffset - 4;
                int disp = movqDisp(dispPos, dispFromCodeStart - extraOffset);
                asm.codeBuffer.reset();
                asm.mov32BitConstant(ARMV7.r12, disp);
                asm.addRegisters(ConditionFlag.Always, false, ARMV7.r12, ARMV7.r12, ARMV7.r15, 0, 0);
                asm.ldr(ConditionFlag.Always, reg, r12, 0);
                byte[] pattern = asm.codeBuffer.close(true);
                byte[] instr = Arrays.copyOfRange(source.code(), pos, pos + pattern.length);
                if (Arrays.equals(pattern, instr)) {
                    result = Arrays.copyOf(result, result.length + 1);
                    result[result.length - 1] = dispPos;
                }
            }
        }
        if (result.length == 0) {
            java.io.PrintWriter writer = null;
            try {
                writer = new java.io.PrintWriter("codebuffer.c", "UTF-8");
                writer.println("unsigned char codeArray[" + source.code().length + "]  = { \n");
                for (int i = 0; i < source.code().length; i += 4) {
                    writer.println("0x" + Integer.toHexString(source.code()[i]) + ", " + "0x" + Integer.toHexString(source.code()[i + 1]) + ", " + "0x" + Integer.toHexString(source.code()[i + 2]) +
                            ", " + "0x" + Integer.toHexString(source.code()[i + 3]) + ",\n");
                }
                writer.println("0xfe, 0xff, 0xff, 0xea };\n");
                writer.close();
            } catch (Exception e) {
                System.err.println(e);
                e.printStackTrace();
                writer.close();
            }
        }
        return result;
    }

    static class PatchInfoARMV7 extends PatchInfo {

        /**
         * Denotes a conditional jump patch. Encoding: {@code cc, pos, targetBCI}.
         */
        static final int JCC = 0;

        /**
         * Denotes an unconditional jump patch. Encoding: {@code pos, targetBCI}.
         */
        static final int JMP = 1;

        /**
         * Denotes a signed int jump table entry. Encoding: {@code pos, jumpTablePos, targetBCI}.
         */
        static final int JUMP_TABLE_ENTRY = 2;

        /**
         * Denotes a signed int jump table entry. Encoding: {@code pos, key, lookupTablePos, targetBCI}.
         */
        static final int LOOKUP_TABLE_ENTRY = 3;

        /**
         * Denotes a movq instruction that loads an object literal. Encoding: {@code dispPos, index}.
         */
        static final int OBJECT_LITERAL = 4;

        void addJCC(ConditionFlag cc, int pos, int targetBCI) {
            ensureCapacity(size + 4);
            data[size++] = JCC;
            data[size++] = cc.ordinal();
            data[size++] = pos;
            data[size++] = targetBCI;
        }

        void addJMP(int pos, int targetBCI) {
            ensureCapacity(size + 3);
            data[size++] = JMP;
            data[size++] = pos;
            data[size++] = targetBCI;
        }

        void addJumpTableEntry(int pos, int jumpTablePos, int targetBCI) {
            ensureCapacity(size + 4);
            data[size++] = JUMP_TABLE_ENTRY;
            data[size++] = pos;
            data[size++] = jumpTablePos;
            data[size++] = targetBCI;
        }

        void addLookupTableEntry(int pos, int key, int lookupTablePos, int targetBCI) {
            ensureCapacity(size + 5);
            data[size++] = LOOKUP_TABLE_ENTRY;
            data[size++] = pos;
            data[size++] = key;
            data[size++] = lookupTablePos;
            data[size++] = targetBCI;
        }

        void addObjectLiteral(int dispPos, int index) {
            ensureCapacity(size + 3);
            data[size++] = OBJECT_LITERAL;
            data[size++] = dispPos;
            data[size++] = index;
        }
    }

    @Override
    protected Kind invokeKind(SignatureDescriptor signature) {
        Kind returnKind = signature.resultKind();
        if (returnKind.stackKind == Kind.INT) {
            return Kind.WORD;
        }
        return returnKind;
    }

    @Override
    protected void do_dup() {
        incStack(1);
        peekWord(ARMV7.r8, 1);
        pokeWord(ARMV7.r8, 0);
    }

    @Override
    protected void do_dup_x1() {
        incStack(1);
        // value1
        peekWord(ARMV7.r8, 1);
        pokeWord(ARMV7.r8, 0);

        // value2
        peekWord(ARMV7.r8, 2);
        pokeWord(ARMV7.r8, 1);

        // value1
        peekWord(ARMV7.r8, 0);
        pokeWord(ARMV7.r8, 2);
    }

    @Override
    protected void do_dup_x2() {
        incStack(1);
        // value1
        peekWord(ARMV7.r8, 1);
        pokeWord(ARMV7.r8, 0);

        // value2
        peekWord(ARMV7.r8, 2);
        pokeWord(ARMV7.r8, 1);

        // value3
        peekWord(ARMV7.r8, 3);
        pokeWord(ARMV7.r8, 2);

        // value1
        peekWord(ARMV7.r8, 0);
        pokeWord(ARMV7.r8, 3);
    }

    @Override
    protected void do_dup2() {
        incStack(2);
        peekWord(ARMV7.r8, 3);
        pokeWord(ARMV7.r8, 1);
        peekWord(ARMV7.r8, 2);
        pokeWord(ARMV7.r8, 0);
    }

    @Override
    protected void do_dup2_x1() {
        incStack(2);
        // value1
        peekWord(ARMV7.r8, 2);
        pokeWord(ARMV7.r8, 0);

        // value2
        peekWord(ARMV7.r8, 3);
        pokeWord(ARMV7.r8, 1);

        // value3
        peekWord(ARMV7.r8, 4);
        pokeWord(ARMV7.r8, 2);

        // value1
        peekWord(ARMV7.r8, 0);
        pokeWord(ARMV7.r8, 3);

        // value2
        peekWord(ARMV7.r8, 1);
        pokeWord(ARMV7.r8, 4);
    }

    @Override
    protected void do_dup2_x2() {
        incStack(2);
        // value1
        peekWord(ARMV7.r8, 2);
        pokeWord(ARMV7.r8, 0);

        // value2
        peekWord(ARMV7.r8, 3);
        pokeWord(ARMV7.r8, 1);

        // value3
        peekWord(ARMV7.r8, 4);
        pokeWord(ARMV7.r8, 2);

        // value4
        peekWord(ARMV7.r8, 5);
        pokeWord(ARMV7.r8, 3);

        // value1
        peekWord(ARMV7.r8, 0);
        pokeWord(ARMV7.r8, 4);

        // value2
        peekWord(ARMV7.r8, 1);
        pokeWord(ARMV7.r8, 5);
    }

    @Override
    protected void do_swap() {
        asm.vmov(ConditionFlag.Always, ARMV7.s31, ARMV7.r9, null, CiKind.Float, CiKind.Int);

        peekWord(ARMV7.r8, 0);
        peekWord(ARMV7.r9, 1);
        pokeWord(ARMV7.r8, 1);
        pokeWord(ARMV7.r9, 0);
        asm.vmov(ConditionFlag.Always, ARMV7.r9, ARMV7.s31, null, CiKind.Int, CiKind.Float);
    }

    @Override
    protected void do_ldc(int index) {
        PoolConstant constant = cp.at(index);
        switch (constant.tag()) {
            case CLASS: {
                ClassConstant classConstant = (ClassConstant) constant;
                if (classConstant.isResolvableWithoutClassLoading(cp)) {
                    Object mirror = ((ClassActor) classConstant.value(cp, index).asObject()).javaClass();
                    incStack(1);
                    assignObject(ARMV7.r8, mirror); // we need to make sure no ARMV7s use scratch
                    pokeObject(ARMV7.r8, 0); // as they will be overwritten by setupscratch
                } else {
                    start(T1XTemplateTag.LDC$reference);
                    assignObject(0, "guard", cp.makeResolutionGuard(index));
                    finish();
                }
                break;
            }
            case INTEGER: {
                IntegerConstant integerConstant = (IntegerConstant) constant;
                do_iconst(integerConstant.value());
                break;
            }
            case LONG: {
                LongConstant longConstant = (LongConstant) constant;
                do_lconst(longConstant.value());
                break;
            }
            case FLOAT: {
                FloatConstant floatConstant = (FloatConstant) constant;
                do_fconst(floatConstant.value());
                break;
            }
            case DOUBLE: {
                DoubleConstant doubleConstant = (DoubleConstant) constant;
                do_dconst(doubleConstant.value());
                break;
            }
            case STRING: {
                StringConstant stringConstant = (StringConstant) constant;
                do_oconst(stringConstant.value);
                break;
            }
            default: {
                assert false : "ldc for unexpected constant tag: " + constant.tag();
                break;
            }
        }
    }

    @Override
    protected void emit(T1XTemplateTag tag) {
        start(tag);
        finish();
    }

    public void emitPrologueTests() {
        /*
         * mimics the functionality of T1XCompilation::compile2
         */
        emitPrologue();

        emitUnprotectMethod();

        // do_profileMethodEntry();

        do_methodTraceEntry();

        // do_synchronizedMethodAcquire();

        // int bci = 0;
        // int endBCI = stream.endBCI();
        // while (bci < endBCI) {
        // int opcode = stream.currentBC();
        // processBytecode(opcode);
        // stream.next();
        // bci = stream.currentBCI();
        // }

        // int epiloguePos = buf.position();

        // do_synchronizedMethodHandler(method, endBCI);

        // if (epiloguePos != buf.position()) {
        // bciToPos[endBCI] = epiloguePos;
        // }
    }

    public void do_iaddTests() {
        // @T1X_TEMPLATE(IADD)
        // public static int iadd(@Slot(1) int value1, @Slot(0) int value2) {
        // return value1 + value2;
        // }
        peekInt(ARMV7.r0, 0);
        decStack(1); // get slot 1
        peekInt(ARMV7.r1, 0);
        decStack(1); // get slot 0
        asm.addRegisters(ConditionFlag.Always, true, ARMV7.r0, ARMV7.r0, ARMV7.r1, 0, 0);
        incStack(1);
        pokeInt(ARMV7.r0, 0); // push the result onto the operand stack.
        // do_iadd();
    }

    public void do_imulTests() {
        // @T1X_TEMPLATE(IADD)
        // public static int iadd(@Slot(1) int value1, @Slot(0) int value2) {
        // return value1 + value2;
        // }

        peekInt(ARMV7.r0, 0);
        decStack(1); // get slot 1
        peekInt(ARMV7.r1, 0);
        decStack(1); // get slot 0
        asm.mul(ConditionFlag.Always, true, ARMV7.r0, ARMV7.r0, ARMV7.r1);
        incStack(1);
        pokeInt(ARMV7.r0, 0); // push the result onto the operand stack.
        // do_iadd();
    }

    public void do_initFrameTests(ClassMethodActor method, CodeAttribute codeAttribute) {
        initFrame(method, codeAttribute);
    }

    public void do_storeTests(int index, Kind kind) {
        do_store(index, kind);
    }

    public void do_loadTests(int index, Kind kind) {
        do_load(index, kind);
    }

    public void do_fconstTests(float value) {
        do_fconst(value);
    }

    public void do_dconstTests(double value) {
        do_dconst(value);
    }

    public void do_iconstTests(int value) {
        do_iconst(value);
    }

    public void do_lconstTests(long value) {
        do_lconst(value);
    }

    public void assignmentTests(CiRegister reg, long value) {
        assignLong(reg, value);
    }

    public void assignDoubleTest(CiRegister reg, double value) {
        assignDouble(reg, value);
    }
}
