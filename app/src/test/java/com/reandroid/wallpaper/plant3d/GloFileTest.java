package com.reandroid.wallpaper.plant3d;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * GLO3 解析器的结构与数值检查。
 *
 * <p>这些数字是拿引擎自己的读取顺序核对出来的：容器各块能被严格消费到块尾，
 * 场景树 557 个节点与 {@code money_plant.txt} 的父子关系一致。任何一条对不上
 * 都说明解析偏移错了，而不是"只是解析不全"。
 */
public class GloFileTest {

    private static final String RELATIVE = "src/main/assets/plant3d/plant3_pot_sm2_nl.glo";

    private static GloFile load() throws IOException {
        File file = new File(RELATIVE);
        if (!file.exists()) {
            file = new File("app/" + RELATIVE);
        }
        return new GloFile(Files.readAllBytes(file.toPath()));
    }

    @Test
    public void parsesContainerCounts() throws IOException {
        GloFile glo = load();
        assertEquals("MaxScene", glo.sceneName);
        assertEquals(89, glo.geometries.size());
        assertEquals(7, glo.materials.size());
        assertEquals(79, glo.skins.size());
        assertEquals(1, glo.roots.size());
        assertEquals("sceneRoot", glo.roots.get(0).name);
    }

    @Test
    public void walksWholeSceneTree() throws IOException {
        GloFile glo = load();
        assertEquals(557, count(glo.roots.get(0)));
        assertEquals(2, glo.roots.get(0).children.size());
    }

    @Test
    public void readsSkinnedAndStaticVertexLayouts() throws IOException {
        GloFile glo = load();
        GloFile.Geometry leaf = glo.geometry("geom-leaf073");
        assertNotNull(leaf);
        assertEquals(44, leaf.stride());
        assertNotNull(leaf.attribute("a_jointIndex0"));
        assertNotNull(leaf.attribute("a_jointWeight3"));
        assertEquals(36, leaf.attribute("a_jointIndex0").offset);

        GloFile.Geometry plane = glo.geometry("geom-Plane001");
        assertNotNull(plane);
        assertEquals(36, plane.stride());
        assertNull(plane.attribute("a_jointIndex0"));
    }

    @Test
    public void bindsSkinToGeometry() throws IOException {
        GloFile glo = load();
        GloFile.Skin skin = glo.skin("geom-leaf073-skin1");
        assertNotNull(skin);
        assertEquals("geom-leaf073", skin.geometry);
        assertEquals(4, skin.jointsPerVertex);
        assertEquals(5, skin.attachments.size());
        assertEquals(16, skin.bindShapeTransform.length);
        assertNotNull(skin.attachments.get(0).joint);
        assertEquals(16, skin.attachments.get(0).inverseBindPose.length);
    }

    @Test
    public void readsMaterialsAndTheirTextures() throws IOException {
        GloFile glo = load();
        GloFile.Material floor = glo.material("floor");
        assertNotNull(floor);
        assertEquals("floor.png", floor.texture);
        GloFile.Material pot = glo.material("pot");
        assertNotNull(pot);
        assertNull(pot.texture);
        assertEquals(1.0f, pot.colours[3], 1e-6f);
    }

    @Test
    public void linksNodesToGeometryMaterialAndSkin() throws IOException {
        GloFile glo = load();
        GloFile.Node skinNode = findSkin(glo.roots.get(0), "geom-leaf073-skin1");
        assertNotNull(skinNode);
        assertEquals("leaf_mtk", skinNode.material);
        assertEquals(1, skinNode.skeleton.size());
        assertEquals("leaf_Bone206", skinNode.skeleton.get(0));

        GloFile.Node floor = findByGeometry(glo.roots.get(0), "geom-Plane001");
        assertNotNull(floor);
        assertEquals("floor", floor.material);
        assertNull(floor.skin);
    }

    private static int count(GloFile.Node node) {
        int total = 1;
        for (GloFile.Node child : node.children) {
            total += count(child);
        }
        return total;
    }

    private static GloFile.Node findSkin(GloFile.Node node, String skin) {
        if (skin.equals(node.skin)) {
            return node;
        }
        for (GloFile.Node child : node.children) {
            GloFile.Node found = findSkin(child, skin);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static GloFile.Node findByGeometry(GloFile.Node node, String geometry) {
        if (geometry.equals(node.geometry)) {
            return node;
        }
        for (GloFile.Node child : node.children) {
            GloFile.Node found = findByGeometry(child, geometry);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
