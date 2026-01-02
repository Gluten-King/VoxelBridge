#version 430

layout(binding=0) uniform sampler2D tex;
in vec2 texCoord;
in flat uint metadata;
in vec4 vColor;
layout(location=0) out vec4 colour;

void main() {
    float mipbias = ((~metadata >> 1) & 1u) * -16.0f;
    mipbias = -16.0f;
    colour = texture(tex, texCoord, mipbias) * vColor;
    if (colour.a < 0.001f && ((metadata & 1u) != 0)) {
        discard;
    }
}
