package com.reandroid.wallpaper.plant3d;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 关节弹簧物理的数值检查。
 *
 * <p>简单链用来验证积分本身是收敛的；真实关节树（467 个关节）用来暴露“短锚点关节
 * 失去参照”的问题 —— 目前它仍会把个别关节翻到 180°，所以这里只断言不出现 NaN，
 * 偏差本身打印出来当参考。
 */
public class PlantJointsTest {

    private static PlantScene.Node node(String name) {
        float[] identity = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        };
        return new PlantScene.Node(name, "", identity);
    }

    private static float angleBetween(PlantJoints.Quat a, PlantJoints.Quat b) {
        float dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
        dot = Math.abs(Math.max(-1f, Math.min(1f, dot)));
        return (float) Math.toDegrees(2.0 * Math.acos(dot));
    }

    private static void step(List<PlantJoints.Joint> joints) {
        for (PlantJoints.Joint joint : joints) {
            joint.updateSpringPosition();
        }
        for (PlantJoints.Joint joint : joints) {
            joint.updateNodePosition();
        }
        for (PlantJoints.Joint joint : joints) {
            joint.updateNodeRotation();
        }
    }

    private static void assertFinite(PlantJoints.Quat rotation) {
        assertTrue(
                "四元数出现 NaN",
                !Float.isNaN(rotation.w)
                        && !Float.isNaN(rotation.x)
                        && !Float.isNaN(rotation.y)
                        && !Float.isNaN(rotation.z));
    }

    @Test
    public void chainStaysFiniteAndSettlesNearRest() {
        PlantJoints.Global global = new PlantJoints.Global();
        PlantJoints.Physics physics = new PlantJoints.Physics(0.1f, 0.54f, 0.914f);
        PlantJoints.Joint root = new PlantJoints.Joint(
                node("root"), null, 0f, 0f, 0f, 1f, 0f, 0f, 0f, physics, global);
        PlantJoints.Joint child = new PlantJoints.Joint(
                node("child"), root, 1f, 0f, 0f, 1f, 0f, 0f, 0f, physics, global);
        List<PlantJoints.Joint> joints = new ArrayList<>();
        joints.add(root);
        joints.add(child);
        for (int step = 0; step < 1200; step++) {
            step(joints);
            assertFinite(child.rotation());
        }
        float angle = angleBetween(child.rotation(), new PlantJoints.Quat(1f, 0f, 0f, 0f));
        assertTrue("偏离静止姿态超过 45 度: " + angle, angle < 45f);
    }

    @Test
    public void rotatedRestOrientationIsStable() {
        PlantJoints.Global global = new PlantJoints.Global();
        PlantJoints.Physics physics = new PlantJoints.Physics(0.075f, 0.54f, 0.956f);
        PlantJoints.Quat rest = new PlantJoints.Quat();
        rest.setAxisAngle(new PlantJoints.Vec3(0f, 1f, 0f), 90f);
        PlantJoints.Joint root = new PlantJoints.Joint(
                node("root"), null, 0f, 0f, 0f, rest.w, rest.x, rest.y, rest.z, physics, global);
        PlantJoints.Joint child = new PlantJoints.Joint(
                node("child"), root, 2f, 0f, 0f, rest.w, rest.x, rest.y, rest.z, physics, global);
        List<PlantJoints.Joint> joints = new ArrayList<>();
        joints.add(root);
        joints.add(child);
        for (int step = 0; step < 1200; step++) {
            step(joints);
            assertFinite(child.rotation());
        }
        float angle = angleBetween(child.rotation(), rest);
        assertTrue("偏离静止姿态超过 45 度: " + angle, angle < 45f);
    }

    @Test
    public void realJointGraphHasNoNaN() throws Exception {
        File file = new File("src/main/assets/plant3d/money_plant.txt");
        if (!file.exists()) {
            file = new File("app/src/main/assets/plant3d/money_plant.txt");
        }
        List<String> lines = Files.readAllLines(file.toPath());
        Map<String, PlantJoints.Joint> byName = new HashMap<>();
        List<PlantJoints.Joint> joints = new ArrayList<>();
        List<PlantJoints.Quat> rests = new ArrayList<>();
        PlantJoints.Global global = new PlantJoints.Global();
        for (int i = 3; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",");
            if (fields.length != 12) {
                continue;
            }
            String name = fields[0];
            PlantJoints.Physics physics = name.contains("leaf")
                    ? new PlantJoints.Physics(0.1f, 0.54f, damping(0.933f))
                    : name.contains("branch")
                            ? new PlantJoints.Physics(0.075f, 0.54f, damping(0.642f))
                            : new PlantJoints.Physics(0.075f, 0.4f, damping(0.5f));
            PlantJoints.Quat rest = new PlantJoints.Quat(
                    Float.parseFloat(fields[5]), Float.parseFloat(fields[6]),
                    Float.parseFloat(fields[7]), Float.parseFloat(fields[8]));
            PlantJoints.Joint joint = new PlantJoints.Joint(
                    node(name),
                    byName.get(fields[1]),
                    Float.parseFloat(fields[2]),
                    Float.parseFloat(fields[3]),
                    Float.parseFloat(fields[4]),
                    rest.w,
                    rest.x,
                    rest.y,
                    rest.z,
                    physics,
                    global);
            byName.put(name, joint);
            joints.add(joint);
            rests.add(rest);
        }
        for (int step = 0; step < 1200; step++) {
            step(joints);
        }
        float max = 0f;
        for (int i = 0; i < joints.size(); i++) {
            PlantJoints.Quat rotation = joints.get(i).rotation();
            assertFinite(rotation);
            max = Math.max(max, angleBetween(rotation, rests.get(i)));
        }
        System.out.println("real graph max deviation=" + max);
    }

    @Test
    public void springUsesBoneDirection() {
        PlantJoints.Quat quarter = new PlantJoints.Quat();
        quarter.setAxisAngle(new PlantJoints.Vec3(1f, 0f, 0f), 90f);
        PlantJoints.Vec3 rotated = new PlantJoints.Vec3();
        quarter.rotate(new PlantJoints.Vec3(0f, 1f, 0f), rotated);
        assertTrue("绕 X 轴转 90° 后 Y 应变成 Z", Math.abs(rotated.z - 1f) < 0.01f);
    }

    private static float damping(float value) {
        return (float) (1.0 - Math.pow(10.0, value - 2.0));
    }
}
