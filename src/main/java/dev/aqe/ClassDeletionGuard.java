package dev.aqe;

import com.android.tools.smali.dexlib2.ValueType;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.debug.LocalInfo;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.instruction.formats.UnknownInstruction;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.*;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import java.io.IOException;
import java.util.*;

/** Checks live references in final class definitions, never stale/unused DEX ID tables. */
final class ClassDeletionGuard {
    private static final int MAX_DETAILS = 30;
    private final Map<String, String> deleted;
    private final List<String> details = new ArrayList<>();
    private long references;

    ClassDeletionGuard(Map<String, String> deleted) { this.deleted = deleted; }

    void scanDex(String dex, Collection<? extends ClassDef> classes) throws IOException {
        for (ClassDef cls : classes) {
            String location = dex + " | " + cls.getType();
            try {
                type(cls.getSuperclass(), location + " | superclass");
                for (String iface : cls.getInterfaces()) type(iface, location + " | interface");
                annotations(cls, location);
                for (Field field : cls.getFields()) {
                    String at = location + "->" + field.getName() + ":" + field.getType();
                    type(field.getType(), at + " | field type");
                    value(field.getInitialValue(), at + " | initial value");
                    annotations(field, at);
                }
                for (Method method : cls.getMethods()) {
                    String at = location + "->" + method.getName() + "("
                            + String.join("", method.getParameterTypes()) + ")" + method.getReturnType();
                    prototype(method.getParameterTypes(), method.getReturnType(), at + " | signature");
                    annotations(method, at);
                    int parameter = 0;
                    for (MethodParameter p : method.getParameters()) {
                        for (Annotation annotation : p.getAnnotations())
                            annotation(annotation, at + " | parameter " + parameter + " | annotation");
                        parameter++;
                    }
                    MethodImplementation code = method.getImplementation();
                    if (code == null) continue;
                    int address = 0;
                    for (Instruction instruction : code.getInstructions()) {
                        String instructionAt = at + " | " + instruction.getOpcode().name
                                + " @0x" + Integer.toHexString(address);
                        if (instruction instanceof UnknownInstruction || instruction.getOpcode().odexOnly())
                            throw new IOException("Cannot inspect unknown/optimized instruction at " + instructionAt);
                        if (instruction instanceof ReferenceInstruction)
                            reference(((ReferenceInstruction) instruction).getReference(), instructionAt);
                        if (instruction instanceof DualReferenceInstruction)
                            reference(((DualReferenceInstruction) instruction).getReference2(), instructionAt + " | second reference");
                        address += instruction.getCodeUnits();
                    }
                    for (TryBlock<? extends ExceptionHandler> block : code.getTryBlocks())
                        for (ExceptionHandler handler : block.getExceptionHandlers())
                            type(handler.getExceptionType(), at + " | catch @0x" + Integer.toHexString(handler.getHandlerCodeAddress()));
                    for (var debug : code.getDebugItems())
                        if (debug instanceof LocalInfo)
                            type(((LocalInfo) debug).getType(), at + " | debug local @0x" + Integer.toHexString(debug.getCodeAddress()));
                }
            } catch (Exception e) {
                throw new IOException("Cannot verify class deletion at " + location + ": " + e.getMessage(), e);
            }
        }
    }

    private void type(String descriptor, String location) {
        if (descriptor == null) return;
        int start = 0;
        while (start < descriptor.length() && descriptor.charAt(start) == '[') start++;
        String component = descriptor.substring(start);
        String operation = deleted.get(component);
        if (operation == null) return;
        references++;
        if (details.size() < MAX_DETAILS)
            details.add("  " + component + " (" + operation + ") <- " + location);
    }

    private void prototype(List<? extends CharSequence> parameters, String result, String location) {
        type(result, location + " return type");
        int index = 0;
        for (CharSequence parameter : parameters) type(parameter.toString(), location + " parameter " + index++);
    }

    private void reference(Reference reference, String location) throws IOException {
        if (reference instanceof TypeReference) {
            type(((TypeReference) reference).getType(), location + " | type");
        } else if (reference instanceof FieldReference) {
            FieldReference field = (FieldReference) reference;
            type(field.getDefiningClass(), location + " | field owner " + field.getName());
            type(field.getType(), location + " | field type " + field.getName());
        } else if (reference instanceof MethodReference) {
            MethodReference method = (MethodReference) reference;
            type(method.getDefiningClass(), location + " | method owner " + method.getName());
            prototype(method.getParameterTypes(), method.getReturnType(), location + " | method " + method.getName());
        } else if (reference instanceof MethodProtoReference) {
            MethodProtoReference proto = (MethodProtoReference) reference;
            prototype(proto.getParameterTypes(), proto.getReturnType(), location + " | method prototype");
        } else if (reference instanceof MethodHandleReference) {
            reference(((MethodHandleReference) reference).getMemberReference(), location + " | method handle");
        } else if (reference instanceof CallSiteReference) {
            CallSiteReference call = (CallSiteReference) reference;
            reference(call.getMethodHandle(), location + " | call site bootstrap");
            reference(call.getMethodProto(), location + " | call site prototype");
            for (EncodedValue argument : call.getExtraArguments()) value(argument, location + " | call site argument");
        } else if (!(reference instanceof StringReference)) {
            throw new IOException("Unsupported reference at " + location + ": " + reference.getClass().getName());
        }
        // A string that happens to contain a class name is not a statically linked reference.
    }

    private void annotations(Annotatable item, String location) throws IOException {
        for (Annotation annotation : item.getAnnotations()) annotation(annotation, location + " | annotation");
    }

    private void annotation(BasicAnnotation annotation, String location) throws IOException {
        type(annotation.getType(), location + " type");
        for (AnnotationElement element : annotation.getElements())
            value(element.getValue(), location + "." + element.getName());
    }

    private void value(EncodedValue value, String location) throws IOException {
        if (value == null) return;
        switch (value.getValueType()) {
            case ValueType.TYPE: type(((TypeEncodedValue) value).getValue(), location); break;
            case ValueType.FIELD: reference(((FieldEncodedValue) value).getValue(), location); break;
            case ValueType.ENUM: reference(((EnumEncodedValue) value).getValue(), location); break;
            case ValueType.METHOD: reference(((MethodEncodedValue) value).getValue(), location); break;
            case ValueType.METHOD_TYPE: reference(((MethodTypeEncodedValue) value).getValue(), location); break;
            case ValueType.METHOD_HANDLE: reference(((MethodHandleEncodedValue) value).getValue(), location); break;
            case ValueType.ARRAY:
                for (EncodedValue item : ((ArrayEncodedValue) value).getValue()) value(item, location + "[]");
                break;
            case ValueType.ANNOTATION: annotation((AnnotationEncodedValue) value, location); break;
            case ValueType.BYTE: case ValueType.SHORT: case ValueType.CHAR: case ValueType.INT:
            case ValueType.LONG: case ValueType.FLOAT: case ValueType.DOUBLE: case ValueType.STRING:
            case ValueType.NULL: case ValueType.BOOLEAN: break;
            default: throw new IOException("Unsupported encoded value at " + location + ": " + value.getValueType());
        }
    }

    /** Literal framework component class names; arbitrary metadata/resource strings are not class references. */
    void scanManifest(AndroidManifestBlock manifest) {
        scanManifestElement(manifest.getManifestElement(), manifest.getPackageName());
    }

    private void scanManifestElement(ResXmlElement element, String packageName) {
        Set<String> names;
        switch (element.getName()) {
            case "application": names = Set.of("name", "backupAgent", "manageSpaceActivity", "appComponentFactory", "zygotePreloadName"); break;
            case "activity": names = Set.of("name", "parentActivityName"); break;
            case "activity-alias": names = Set.of("targetActivity", "parentActivityName"); break;
            case "service": case "receiver": case "provider": case "instrumentation": names = Set.of("name"); break;
            default: names = Set.of();
        }
        var attributes = element.getAttributes();
        while (attributes.hasNext()) {
            var attribute = attributes.next();
            if (!"http://schemas.android.com/apk/res/android".equals(attribute.getUri())
                    || !names.contains(attribute.getName(false))
                    || attribute.getValueType() != com.reandroid.arsc.value.ValueType.STRING) continue;
            String name = attribute.getValueAsString();
            if (name == null || name.isEmpty()) continue;
            if (name.startsWith(".")) name = packageName + name;
            else if (!name.contains(".")) name = packageName + "." + name;
            type(DexEditor.descriptor(name), "AndroidManifest.xml | " + element.getName() + " android:" + attribute.getName(false));
        }
        var children = element.getElements();
        while (children.hasNext()) scanManifestElement(children.next(), packageName);
    }

    void requireNoReferences() throws IOException {
        if (references == 0) return;
        String remaining = references > details.size() ? "\n  ... " + (references - details.size()) + " more reference(s)" : "";
        throw new IOException("Refusing class deletion: " + references + " remaining direct reference(s).\n"
                + String.join("\n", details) + remaining
                + "\nUpdate/remove the referring classes or Manifest entries in the same patch, or keep the target classes. No output published.");
    }
}
