#version 300 es
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

precision mediump float;

out vec4 fragColor;
uniform sampler2D sTexture;

in vec3 v_Color;
in vec2 texture_coordinate;

void main(void){
    fragColor = texture(sTexture, vec2(texture_coordinate.x, texture_coordinate.y));
    fragColor = vec4(fragColor.xyz * v_Color, fragColor.a);
}
