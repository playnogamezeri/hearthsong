package com.hearthsong.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tanh

/** Lightweight stateful monitor color chain for the combined mono mic input. */
internal object KaraokeLiveProcessor {
    private data class Preset(val warmth:Double,val brightness:Double,val compression:Double,val reverb:Double,val drive:Double)
    private val presets=listOf(Preset(.10,.08,.24,.10,0.0),Preset(.04,.22,.38,.18,.02),Preset(.16,.02,.30,.30,0.0),Preset(0.0,.16,.48,.12,.12))
    private var low=0.0
    private var envelope=0.0
    private var gain=1.0
    private val delay=DoubleArray(560)
    private var delayPosition=0
    @Synchronized fun reset(){low=0.0;envelope=0.0;gain=1.0;delay.fill(0.0);delayPosition=0}
    @Synchronized fun process(pcm:ByteArray,presetIndex:Int):ByteArray{
        if(pcm.size<2)return pcm
        val p=presets[presetIndex.coerceIn(0,presets.lastIndex)]
        val output=ByteArray(pcm.size)
        var i=0
        while(i+1<pcm.size){
            val raw=((pcm[i+1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()/32768.0
            low+=(raw-low)*.035
            var colored=(raw-low)*(1.0+p.brightness)+low*(1.0+p.warmth)
            val level=abs(colored)
            envelope+=(level-envelope)*(if(level>envelope).16 else .008)
            val threshold=.72-p.compression*.42
            val ratio=1.0+p.compression*4.0
            val target=if(envelope>threshold)(threshold+(envelope-threshold)/ratio)/max(envelope,1e-8) else 1.0
            gain+=(target-gain)*(if(target<gain).18 else .006)
            colored*=gain
            if(p.drive>.001)colored=tanh(colored*(1.0+p.drive*3.0))/tanh(1.0+p.drive*3.0)
            val delayed=delay[delayPosition]
            delay[delayPosition]=colored+delayed*.28
            delayPosition=(delayPosition+1)%delay.size
            val mixed=colored*(1.0-p.reverb*.22)+delayed*p.reverb*.22
            val sample=(mixed.coerceIn(-.96,.96)*32767.0).roundToInt()
            output[i]=(sample and 255).toByte();output[i+1]=((sample shr 8) and 255).toByte();i+=2
        }
        if(i<pcm.size)output[i]=pcm[i]
        return output
    }
}
