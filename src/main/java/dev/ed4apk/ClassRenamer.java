package dev.ed4apk;

import com.android.tools.smali.dexlib2.ValueType;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.reference.CallSiteReference;
import com.android.tools.smali.dexlib2.iface.value.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.immutable.value.*;
import com.android.tools.smali.dexlib2.rewriter.*;
import java.io.IOException;

/** Renames descriptors, not arbitrary strings. Immutable results avoid lazy overlay aliasing. */
final class ClassRenamer {
    static String descriptor(String name) throws IOException {
        String type = DexEditor.descriptor(name);
        if (!type.startsWith("L") || !type.endsWith(";"))
            throw new IOException("Invalid class name: " + name);
        String[] parts = type.substring(1, type.length() - 1).split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || !part.codePoints().allMatch(ClassRenamer::nameCharacter))
                throw new IOException("Invalid class name: " + name);
        }
        return type;
    }

    // DEX SimpleNameChar, excluding spaces introduced in DEX 040 so names also work in older DEX.
    // https://source.android.com/docs/core/runtime/dex-format#simplename
    private static boolean nameCharacter(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '$' || c == '-' || c == '_' || c >= 0x00a1 && c <= 0x1fff
                || c >= 0x2010 && c <= 0x2027 || c >= 0x2030 && c <= 0xd7ff
                || c >= 0xe000 && c <= 0xffef || c >= 0x10000 && c <= 0x10ffff;
    }

    static void validate(String from, String to) throws IOException {
        if (from.equals(to)) throw new IOException("Source and destination class must differ");
        if (!from.substring(0, from.lastIndexOf('/') + 1).equals(to.substring(0, to.lastIndexOf('/') + 1)))
            throw new IOException("Cross-package rename is not supported; keep the original package");
    }

    private final DexRewriter rewriter;
    ClassRenamer(String from, String to) {
        rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return new TypeRewriter() {
                    @Override protected String rewriteUnwrappedType(String value) { return value.equals(from) ? to : value; }
                };
            }
            @Override public Rewriter<EncodedValue> getEncodedValueRewriter(Rewriters rewriters) {
                return new EncodedValueRewriter(rewriters) {
                    @Override public EncodedValue rewrite(EncodedValue value) {
                        if (value.getValueType() == ValueType.METHOD_TYPE)
                            return new ImmutableMethodTypeEncodedValue(ImmutableMethodProtoReference.of(
                                    RewriterUtils.rewriteMethodProtoReference(rewriters.getTypeRewriter(),
                                            ((MethodTypeEncodedValue) value).getValue())));
                        if (value.getValueType() == ValueType.METHOD_HANDLE)
                            return new ImmutableMethodHandleEncodedValue(ImmutableMethodHandleReference.of(
                                    RewriterUtils.rewriteMethodHandleReference(rewriters,
                                            ((MethodHandleEncodedValue) value).getValue())));
                        return super.rewrite(value);
                    }
                };
            }
            @Override public Rewriter<CallSiteReference> getCallSiteReferenceRewriter(Rewriters rewriters) {
                // dexlib2's default call-site helper handles only a subset of encoded values.
                return call -> new ImmutableCallSiteReference(call.getName(),
                        RewriterUtils.rewriteMethodHandleReference(rewriters, call.getMethodHandle()), call.getMethodName(),
                        RewriterUtils.rewriteMethodProtoReference(rewriters.getTypeRewriter(), call.getMethodProto()),
                        RewriterUtils.rewriteList(rewriters.getEncodedValueRewriter(), call.getExtraArguments()));
            }
        });
    }
    ClassDef rewrite(ClassDef cls) { return ImmutableClassDef.of(rewriter.getClassDefRewriter().rewrite(cls)); }
}
