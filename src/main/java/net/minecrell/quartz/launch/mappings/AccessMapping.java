/*
 * Quartz
 * Copyright (c) 2015, Minecrell <https://github.com/Minecrell>
 *
 * Based on Sponge and SpongeAPI, licensed under the MIT License (MIT).
 * Copyright (c) SpongePowered.org <http://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.minecrell.quartz.launch.mappings;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public class AccessMapping {

    private final AccessModifier access;
    private final boolean isFinal;

    public AccessMapping(AccessModifier access, boolean finalModifier) {
        this.access = access;
        this.isFinal = finalModifier;
    }

    public AccessModifier getAccess() {
        return access;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public int transform(int access) {
        return AccessModifier.setFinal(this.access.transform(access), isFinal);
    }

    public void transform(ClassNode classNode) {
        classNode.access = transform(classNode.access);
    }

    public void transform(MethodNode methodNode) {
        methodNode.access = transform(methodNode.access);
    }

    public void transform(FieldNode fieldNode) {
        fieldNode.access = transform(fieldNode.access);
    }

}
