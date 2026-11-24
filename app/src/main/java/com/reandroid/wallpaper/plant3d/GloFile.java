package com.reandroid.wallpaper.plant3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the GLO3 scene format used by the money plant model.
 *
 * <p>Layout of the container (all fields little endian):
 *
 * <pre>
 * GLO3 u32 totalLength u32 flags
 * chunk := tag[4] u32 length payload
 * chunks: INFO, LMAT, LGEO, LCTL, LLGT, SCNE
 * </pre>
 *
 * The node record layout is version 0.14: name, scoped name, 4x4 matrix,
 * translation, quaternion, scale, four reference blocks, child count and the
 * child nodes. The matrix and the translation/rotation/scale are both present
 * and the engine applies the matrix last, so only the matrix is used here.
 */
final class GloFile {

    static final class Attribute {
        final String name;
        final int type;
        final int offset;
        final int stride;

        Attribute(String name, int type, int offset, int stride) {
            this.name = name;
            this.type = type;
            this.offset = offset;
            this.stride = stride;
        }
    }

    static final class Geometry {
        final String name;
        final int vertexCount;
        final List<Attribute> attributes;
        final byte[] vertexBytes;
        final short[] indices;

        Geometry(
                String name,
                int vertexCount,
                List<Attribute> attributes,
                byte[] vertexBytes,
                short[] indices) {
            this.name = name;
            this.vertexCount = vertexCount;
            this.attributes = attributes;
            this.vertexBytes = vertexBytes;
            this.indices = indices;
        }

        int stride() {
            return attributes.isEmpty() ? 0 : attributes.get(0).stride;
        }

        Attribute attribute(String name) {
            for (Attribute attribute : attributes) {
                if (attribute.name.equals(name)) {
                    return attribute;
                }
            }
            return null;
        }
    }

    static final class Material {
        final String name;
        String texture;
        float[] colours = new float[0];

        Material(String name) {
            this.name = name;
        }
    }

    static final class Node {
        final String name;
        final String joint;
        final float[] matrix;
        final List<Node> children = new ArrayList<>();
        final List<String> skeleton = new ArrayList<>();
        String geometry;
        String material;
        String skin;

        Node(String name, String joint, float[] matrix) {
            this.name = name;
            this.joint = joint;
            this.matrix = matrix;
        }
    }

    static final class Attachment {
        final String joint;
        final float[] inverseBindPose;

        Attachment(String joint, float[] inverseBindPose) {
            this.joint = joint;
            this.inverseBindPose = inverseBindPose;
        }
    }

    static final class Skin {
        final String name;
        final String geometry;
        final int jointsPerVertex;
        final float[] bindShapeTransform;
        final List<Attachment> attachments = new ArrayList<>();

        Skin(String name, String geometry, int jointsPerVertex, float[] bindShapeTransform) {
            this.name = name;
            this.geometry = geometry;
            this.jointsPerVertex = jointsPerVertex;
            this.bindShapeTransform = bindShapeTransform;
        }
    }

    private final byte[] data;
    private final int length;

    final List<Geometry> geometries = new ArrayList<>();
    final List<Material> materials = new ArrayList<>();
    final List<Skin> skins = new ArrayList<>();
    final List<Node> roots = new ArrayList<>();
    String sceneName = "";
    String version = "";

    GloFile(byte[] data) {
        this.data = data;
        this.length = data.length;
        if (length < 12 || data[0] != 'G' || data[1] != 'L' || data[2] != 'O' || data[3] != '3') {
            throw new IllegalArgumentException("not a GLO3 file");
        }
        parse();
    }

    private void parse() {
        for (int[] chunk : chunks(12, length)) {
            int body = chunk[0];
            int end = chunk[1];
            switch (tagAt(body - 8)) {
                case "INFO":
                    parseInfo(body, end);
                    break;
                case "LMAT":
                    parseMaterials(body, end);
                    break;
                case "LGEO":
                    parseGeometries(body, end);
                    break;
                case "LCTL":
                    parseSkins(body, end);
                    break;
                case "SCNE":
                    parseScene(body, end);
                    break;
                default:
                    break;
            }
        }
    }

    private void parseInfo(int body, int end) {
        int pos = body;
        while (pos + 4 <= end) {
            String value = string(pos);
            if (value == null) {
                break;
            }
            if (version.isEmpty() && value.indexOf('.') > 0) {
                version = value;
            }
            pos = stringEnd(pos);
        }
    }

    // -- geometry --------------------------------------------------------

    private void parseGeometries(int body, int end) {
        int pos = body;
        int count = intAt(pos);
        pos += 4;
        for (int i = 0; i < count && pos + 8 <= end; i++) {
            if (!"GEOM".equals(tagAt(pos))) {
                return;
            }
            int geomEnd = pos + 8 + intAt(pos + 4);
            Geometry geometry = readGeometry(pos + 8, geomEnd);
            if (geometry != null) {
                geometries.add(geometry);
            }
            pos = geomEnd;
        }
    }

    private Geometry readGeometry(int start, int end) {
        int pos = stringEnd(start);
        String name = string(start);
        if (name == null || pos + 8 > end || !"VSET".equals(tagAt(pos))) {
            return null;
        }
        int vertexCount = intAt(pos + 8);
        int attributeCount = intAt(pos + 12);
        pos += 16;
        List<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < attributeCount; i++) {
            String attributeName = string(pos + 8);
            pos = stringEnd(pos + 8);
            int type = intAt(pos);
            int offset = intAt(pos + 8);
            int stride = intAt(pos + 12);
            pos += 20;
            if (attributeName != null) {
                attributes.add(new Attribute(attributeName, type, offset, stride));
            }
        }
        int vertexLength = intAt(pos + 4);
        byte[] vertices = new byte[vertexLength];
        System.arraycopy(data, pos + 8, vertices, 0, vertexLength);
        pos += 8 + vertexLength;

        short[] indices = new short[0];
        int ibuf = indexOf("IBUF", pos, end);
        if (ibuf >= 0) {
            int bufferLength = intAt(ibuf + 4);
            int start2 = ibuf + 12;
            int count = Math.max(0, (bufferLength - 4) / 2);
            indices = new short[count];
            for (int i = 0; i < count; i++) {
                indices[i] = (short)
                        (data[start2 + i * 2] & 0xff | (data[start2 + i * 2 + 1] & 0xff) << 8);
            }
        }
        return new Geometry(name, vertexCount, attributes, vertices, indices);
    }

    // -- skins -----------------------------------------------------------

    private void parseSkins(int body, int end) {
        int count = intAt(body);
        int pos = body + 4;
        for (int i = 0; i < count && pos + 8 <= end; i++) {
            if (!"SKIN".equals(tagAt(pos))) {
                return;
            }
            int skinEnd = pos + 8 + intAt(pos + 4);
            String name = string(pos + 8);
            int p = stringEnd(pos + 8);
            String geometry = string(p);
            p = stringEnd(p);
            int jointsPerVertex = intAt(p);
            p += 4;
            float[] bindShape = floats(p, 16);
            p += 64;
            int attachmentCount = intAt(p);
            p += 4;
            Skin skin = new Skin(name, geometry, jointsPerVertex, bindShape);
            for (int j = 0; j < attachmentCount; j++) {
                if (!"ATCH".equals(tagAt(p))) {
                    break;
                }
                int attachmentEnd = p + 8 + intAt(p + 4);
                String joint = string(p + 8);
                int q = stringEnd(p + 8);
                skin.attachments.add(new Attachment(joint, floats(q, 16)));
                p = attachmentEnd;
            }
            skins.add(skin);
            pos = skinEnd;
        }
    }

    // -- materials -------------------------------------------------------

    private void parseMaterials(int body, int end) {
        int pos = body + 4;
        while (pos + 8 <= end && "MATL".equals(tagAt(pos))) {
            int materialEnd = pos + 8 + intAt(pos + 4);
            String name = string(pos + 8);
            if (name != null) {
                Material material = new Material(name);
                int smat = indexOf("SMAT", pos + 8, materialEnd);
                if (smat >= 0) {
                    material.colours = floats(smat + 8, 16);
                }
                int smpl = indexOf("SMPL", pos + 8, materialEnd);
                if (smpl >= 0) {
                    material.texture = textureName(smpl + 8, materialEnd);
                }
                materials.add(material);
            }
            pos = materialEnd;
        }
    }

    private String textureName(int start, int end) {
        for (String suffix : new String[] {".png", ".jpg", ".jpeg"}) {
            int at = indexOf(suffix, start, end);
            if (at >= 0) {
                int begin = at;
                while (begin > start && data[begin - 1] >= 32 && data[begin - 1] < 127) {
                    begin--;
                }
                return new String(data, begin, at + suffix.length() - begin);
            }
        }
        return null;
    }

    // -- scene -----------------------------------------------------------

    private void parseScene(int body, int end) {
        String name = string(body);
        if (name != null) {
            sceneName = name;
        }
        int pos = stringEnd(body);
        while (pos + 8 <= end && "NODE".equals(tagAt(pos))) {
            roots.add(readNode(pos));
            pos = pos + 8 + intAt(pos + 4);
        }
    }

    private Node readNode(int start) {
        String name = string(start + 8);
        int pos = stringEnd(start + 8);
        String joint = string(pos);
        pos = stringEnd(pos);
        float[] matrix = floats(pos, 16);
        pos += 64;
        pos += 40; // translation, rotation, scale
        Node node = new Node(name == null ? "" : name, joint == null ? "" : joint, matrix);
        pos = readReferences(node, pos);
        int childCount = intAt(pos);
        pos += 4;
        for (int i = 0; i < childCount; i++) {
            if (!"NODE".equals(tagAt(pos))) {
                break;
            }
            node.children.add(readNode(pos));
            pos = pos + 8 + intAt(pos + 4);
        }
        return node;
    }

    private int readReferences(Node node, int pos) {
        int count = intAt(pos);
        pos += 4;
        for (int i = 0; i < count; i++) {
            if (!"GREF".equals(tagAt(pos))) {
                break;
            }
            int end = pos + 8 + intAt(pos + 4);
            node.geometry = string(pos + 8);
            int p = stringEnd(pos + 8);
            int materialCount = intAt(p);
            p += 4;
            for (int j = 0; j < materialCount; j++) {
                String material = string(p);
                p = stringEnd(p);
                if (j == 0) {
                    node.material = material;
                }
            }
            pos = end;
        }
        count = intAt(pos);
        pos += 4;
        for (int i = 0; i < count; i++) {
            if (!"SREF".equals(tagAt(pos))) {
                break;
            }
            int end = pos + 8 + intAt(pos + 4);
            node.skin = string(pos + 8);
            int p = stringEnd(pos + 8);
            int skeletonCount = intAt(p);
            p += 4;
            for (int j = 0; j < skeletonCount; j++) {
                String name = string(p);
                p = stringEnd(p);
                if (name != null) {
                    node.skeleton.add(name);
                }
            }
            int materialCount = intAt(p);
            p += 4;
            for (int j = 0; j < materialCount; j++) {
                String material = string(p);
                p = stringEnd(p);
                if (j == 0) {
                    node.material = material;
                }
            }
            pos = end;
        }
        for (int tag = 0; tag < 2; tag++) { // LREF, CREF
            count = intAt(pos);
            pos += 4;
            for (int i = 0; i < count; i++) {
                pos = pos + 8 + intAt(pos + 4);
            }
        }
        return pos;
    }

    // -- helpers ---------------------------------------------------------

    private String tagAt(int pos) {
        if (pos < 0 || pos + 4 > length) {
            return "";
        }
        return new String(data, pos, 4);
    }

    private int intAt(int pos) {
        return (data[pos] & 0xff)
                | (data[pos + 1] & 0xff) << 8
                | (data[pos + 2] & 0xff) << 16
                | (data[pos + 3] & 0xff) << 24;
    }

    private float[] floats(int pos, int count) {
        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            result[i] = Float.intBitsToFloat(intAt(pos + i * 4));
        }
        return result;
    }

    private String string(int pos) {
        if (pos + 4 > length) {
            return null;
        }
        int size = intAt(pos);
        if (size < 0 || size > 4096 || pos + 4 + size > length) {
            return null;
        }
        int end = pos + 4 + size;
        while (end > pos + 4 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, pos + 4, end - pos - 4);
    }

    private int stringEnd(int pos) {
        return pos + 4 + intAt(pos);
    }

    private int indexOf(String text, int start, int end) {
        int last = Math.min(end, length) - text.length();
        outer:
        for (int i = Math.max(0, start); i <= last; i++) {
            for (int j = 0; j < text.length(); j++) {
                if (data[i + j] != text.charAt(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Returns {payloadStart, payloadEnd} for every chunk in the range. */
    private List<int[]> chunks(int start, int end) {
        List<int[]> result = new ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            int chunkEnd = pos + 8 + intAt(pos + 4);
            if (chunkEnd > end || chunkEnd < pos) {
                break;
            }
            result.add(new int[] {pos + 8, chunkEnd});
            pos = chunkEnd;
        }
        return result;
    }

    Geometry geometry(String name) {
        for (Geometry geometry : geometries) {
            if (geometry.name.equals(name)) {
                return geometry;
            }
        }
        return null;
    }

    Skin skin(String name) {
        for (Skin skin : skins) {
            if (skin.name.equals(name)) {
                return skin;
            }
        }
        return null;
    }

    Material material(String name) {
        for (Material material : materials) {
            if (material.name.equals(name)) {
                return material;
            }
        }
        return null;
    }
}
