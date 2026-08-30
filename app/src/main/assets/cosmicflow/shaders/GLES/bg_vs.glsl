/*********************************************************************
 *  ____                      _____      _                           *
 * / ___|  ___  _ __  _   _  | ____|_ __(_) ___ ___ ___  ___  _ __   *
 * \___ \ / _ \| '_ \| | | | |  _| | '__| |/ __/ __/ __|/ _ \| '_ \  *
 *  ___) | (_) | | | | |_| | | |___| |  | | (__\__ \__ \ (_) | | | | *
 * |____/ \___/|_| |_|\__, | |_____|_|  |_|\___|___/___/\___/|_| |_| *
 *                    |___/                                          *
 *                                                                   *
 *********************************************************************
 * Copyright 2011 Sony Ericsson Mobile Communications AB.            *
 * All rights, including trade secret rights, reserved.              *
 *********************************************************************/

attribute vec4 vPosition;

uniform vec3 u_PrimaryColor;
uniform float u_xoffset;
uniform vec4 u_Time_HeightScale_Color_XOffset;

varying vec3 v_Color;

attribute vec2 texCoord;
varying vec2 texture_coordinate;

void main(){
    // 原版在文件作用域用 uniform 初始化别名,现代编译器禁止,移入 main()(语义不变)
    float u_Time = u_Time_HeightScale_Color_XOffset.x;
    float u_HeightScale = u_Time_HeightScale_Color_XOffset.y;
    float u_XOffset = u_Time_HeightScale_Color_XOffset.w;
    float u_ColorIndex = u_Time_HeightScale_Color_XOffset.z;

    gl_Position = vec4(1.0, u_HeightScale, 1.0, 1.0) * (vPosition + vec4(0.0, -0.2 * sin(vPosition.y + u_XOffset * 2.0 + u_Time + vPosition.x), 0.0, 0.0));
    v_Color = u_PrimaryColor;
    texture_coordinate = vec2(texCoord.x, texCoord.y);
}
