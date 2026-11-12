package com.hearthsong.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

/** Offline experimental pitch correction and whole-mix tone processing for mono 16-bit PCM WAV. */
internal object AutoTuneProcessor {
    data class ToneSettings(
        val bassDb: Double = 0.0,
        val trebleDb: Double = 0.0,
        val reverb: Double = 0.0,
        val compression: Double = 0.0,
        val formantColor: Double = 0.0,
        val presenceDb: Double = 0.0,
        val deEss: Double = 0.0,
        val saturation: Double = 0.0
    )

    data class KeyEstimate(val key: Int, val scale: Int, val confidence: Double, val noteCount: Int)
    fun detectKey(input: File): KeyEstimate {
        val pair=readMonoWav(input); val rate=pair.first; val samples=pair.second
        val hist=DoubleArray(12); var valid=0; val frameSize=2048; var start=0
        while(start+frameSize<=samples.size){
            val frame=ShortArray(frameSize){samples[start+it]}
            val rms=sqrt(frame.sumOf{it.toDouble()*it}/frameSize)
            if(rms>220.0){val hz=estimate(frame,rate);if(hz in 70.0..1000.0){
                val midi=(69.0+12.0*ln(hz/440.0)/ln(2.0)).roundToInt()
                hist[((midi%12)+12)%12]+=minOf(rms,5000.0);valid++
            }}
            start+=1024
        }
        if(valid<4)error("Not enough clear pitched audio to estimate a key. Try a longer, clearer recording.")
        val major=intArrayOf(6,2,3,2,5,2,3,6,2,4,2,3)
        val minor=intArrayOf(6,2,3,5,2,3,2,5,3,2,4,3)
        var bestKey=0;var bestScale=0;var best=Double.NEGATIVE_INFINITY;var second=Double.NEGATIVE_INFINITY
        for(k in 0..11)for(s in 0..1){val profile=if(s==0)major else minor;var score=0.0
            for(i in 0..11)score+=hist[(i+k)%12]*profile[i]
            if(score>best){second=best;best=score;bestKey=k;bestScale=s}else if(score>second)second=score
        }
        val confidence=if(best>0)((best-second).coerceAtLeast(0.0)/best).coerceIn(0.0,1.0)else 0.0
        return KeyEstimate(bestKey,bestScale,confidence,valid)
    }
    fun process(input: File, output: File, key: Int, scale: Int, strength: Double, tone: ToneSettings, transposeSemitones: Int = 0): Int {
        RandomAccessFile(input, "r").use { raf ->
            if (raf.length() < 44 || readAscii(raf,0,4)!="RIFF" || readAscii(raf,8,4)!="WAVE") error("Only standard WAV recordings are supported.")
            var offset=12L; var dataOffset=-1L; var dataSize=0L
            var rate=0; var channels=0; var bits=0; var format=0
            while(offset+8<=raf.length()){
                val id=readAscii(raf,offset,4); val size=readU32(raf,offset+4); val body=offset+8
                when(id){
                    "fmt "->{raf.seek(body);format=readU16(raf);channels=readU16(raf);rate=readU32(raf,body+4).toInt();raf.seek(body+14);bits=readU16(raf)}
                    "data"->{dataOffset=body;dataSize=size;break}
                }
                offset=body+size+(size and 1L)
            }
            if(format!=1||channels!=1||bits!=16) error("Auto-Tune supports mono 16-bit WAV recordings only.")
            if(rate !in 8000..48000||dataOffset<0) error("The WAV audio data is incomplete.")
            val n=minOf(dataSize/2,Int.MAX_VALUE.toLong()).toInt()
            if(n<2048) error("The recording is too short to tune.")
            val samples=ShortArray(n);raf.seek(dataOffset)
            for(i in 0 until n){val lo=raf.readUnsignedByte();val hi=raf.readUnsignedByte();samples[i]=((hi shl 8) or lo).toShort()}
            val shifted=if(transposeSemitones!=0)transposeWithSpeedChange(samples,transposeSemitones)else samples
            val (pitched,sections)=correct(shifted,rate,key,scale,strength)
            val processed=EmotionVocalEngine.process(pitched,rate,tone)
            writeWav(output,processed,rate)
            return sections
        }
    }

    private fun correct(input:ShortArray,rate:Int,key:Int,scale:Int,strength:Double):Pair<ShortArray,Int>{
        if(strength<=0.0)return input to 0
        val frame=2048;val hop=1024;val half=frame/2
        val output=DoubleArray(input.size);val weights=DoubleArray(input.size)
        val notes=when(scale){0->intArrayOf(0,2,4,5,7,9,11);1->intArrayOf(0,2,3,5,7,8,10);else->IntArray(12){it}}
        var corrected=0;var center=0
        while(center<input.size){
            val source=ShortArray(frame);var energy=0.0
            for(j in 0 until frame){val idx=(center-half+j).coerceIn(0,input.lastIndex);source[j]=input[idx];energy+=source[j].toDouble()*source[j]}
            val hz=if(sqrt(energy/frame)>180.0)estimate(source,rate)else 0.0
            var ratio=1.0
            if(hz>0){
                val midi=69.0+12.0*ln(hz/440.0)/ln(2.0)
                val target=nearest(midi,key,notes)
                val semitones=(target-midi).coerceIn(-1.0,1.0)*strength
                if(abs(semitones)>0.025){ratio=2.0.pow(semitones/12.0);corrected++}
            }
            for(j in 0 until frame){
                val outIndex=center-half+j
                if(outIndex !in input.indices)continue
                val pos=(half+(j-half)*ratio).coerceIn(0.0,(frame-1).toDouble())
                val left=floor(pos).toInt();val right=(left+1).coerceAtMost(frame-1);val frac=pos-left
                val sample=source[left]*(1-frac)+source[right]*frac
                val win=0.5-0.5*cos(2.0*Math.PI*j/(frame-1))
                output[outIndex]+=sample*win;weights[outIndex]+=win
            }
            center+=hop
        }
        val result=ShortArray(input.size)
        for(i in input.indices){val v=if(weights[i]>1e-8)output[i]/weights[i] else input[i].toDouble();result[i]=v.roundToInt().coerceIn(-32768,32767).toShort()}
        return result to corrected
    }

    /**
     * Pitch-shifts by resampling, then restores the original duration with WSOLA.
     * WSOLA aligns overlapping waveform sections to preserve local pitch while
     * changing their spacing in time. This is a lightweight mono implementation;
     * large shifts and dense guitar chords can still produce audible artifacts.
     */
    private fun transposeWithSpeedChange(input:ShortArray,semitones:Int):ShortArray{
        val pitchRatio=2.0.pow(semitones/12.0)
        val resampledSize=(input.size/pitchRatio).roundToInt().coerceAtLeast(1)
        val resampled=ShortArray(resampledSize){i->
            val pos=i*pitchRatio
            val left=pos.toInt().coerceIn(0,input.lastIndex)
            val right=(left+1).coerceAtMost(input.lastIndex)
            val frac=pos-left
            (input[left]*(1.0-frac)+input[right]*frac).roundToInt().coerceIn(-32768,32767).toShort()
        }
        return stretchWsola(resampled,input.size,pitchRatio)
    }

    /** Waveform-similarity overlap-add time stretch; output has exactly targetLength samples. */
    private fun stretchWsola(input:ShortArray,targetLength:Int,stretch:Double):ShortArray{
        if(input.isEmpty()||targetLength<=0)return ShortArray(0)
        if(input.size<1024||abs(stretch-1.0)<0.0001){
            return ShortArray(targetLength){i->input[(i.toLong()*input.size/targetLength).toInt().coerceIn(0,input.lastIndex)]}
        }
        val frame=1024
        val overlap=512
        val synthesisHop=frame-overlap
        val analysisHop=(synthesisHop/stretch).roundToInt().coerceAtLeast(1)
        val search=160
        val accum=DoubleArray(targetLength+frame)
        val weights=DoubleArray(targetLength+frame)
        var outputPos=0
        var inputPos=0
        var first=true
        while(outputPos<targetLength){
            var chosen=inputPos.coerceIn(0,(input.size-frame).coerceAtLeast(0))
            if(!first){
                val expected=(inputPos+analysisHop).coerceIn(0,(input.size-frame).coerceAtLeast(0))
                var bestScore=Double.NEGATIVE_INFINITY
                val low=(expected-search).coerceAtLeast(0)
                val high=(expected+search).coerceAtMost((input.size-frame).coerceAtLeast(0))
                for(candidate in low..high step 4){
                    var dot=0.0;var aa=0.0;var bb=0.0
                    val count=minOf(overlap,targetLength-outputPos)
                    for(j in 0 until count){
                        val a=if(outputPos+j<accum.size&&weights[outputPos+j]>1e-9)accum[outputPos+j]/weights[outputPos+j] else 0.0
                        val b=input[candidate+j].toDouble()
                        dot+=a*b;aa+=a*a;bb+=b*b
                    }
                    val score=if(aa>1.0&&bb>1.0)dot/sqrt(aa*bb)else 0.0
                    if(score>bestScore){bestScore=score;chosen=candidate}
                }
            }
            val count=minOf(frame,input.size-chosen,targetLength-outputPos)
            for(j in 0 until count){
                val phase=j.toDouble()/(frame-1)
                val window=if(first){
                    if(phase<0.5)1.0 else 0.5+0.5*cos(Math.PI*(phase-0.5)*2.0)
                }else if(j<overlap){
                    0.5-0.5*cos(Math.PI*j/overlap)
                }else{
                    1.0
                }
                accum[outputPos+j]+=input[chosen+j].toDouble()*window
                weights[outputPos+j]+=window
            }
            first=false
            inputPos=chosen
            outputPos+=synthesisHop
        }
        return ShortArray(targetLength){i->
            val value=if(weights[i]>1e-8)accum[i]/weights[i] else input[minOf(input.lastIndex,(i.toLong()*input.size/targetLength).toInt())].toDouble()
            value.roundToInt().coerceIn(-32768,32767).toShort()
        }
    }
    private fun applyTone(input:ShortArray,rate:Int,s:ToneSettings):ShortArray{
        val x=DoubleArray(input.size){input[it].toDouble()/32768.0}
        // Gentle low/high shelving-style EQ, applied to the complete mix.
        if(abs(s.bassDb)>0.01) biquad(x,rate,150.0,0.8,s.bassDb,"lowshelf")
        if(abs(s.trebleDb)>0.01) biquad(x,rate,3800.0,0.8,s.trebleDb,"highshelf")
        // Formant-style resonance coloration: two broad vocal-range peaks.
        // This colors the spectrum; it is not a true pitch-independent formant shifter.
        if(s.formantColor>0.01){
            val amount=(s.formantColor/100.0)*5.0
            biquad(x,rate,720.0,1.0,amount,"peak")
            biquad(x,rate,1450.0,1.1,amount*0.8,"peak")
        }
        // Simple feed-forward compressor with soft-knee-like gain smoothing.
        if(s.compression>0.01){
            val strength=(s.compression/100.0)
            val threshold=0.72-0.42*strength
            val ratio=1.0+strength*5.0
            var gain=1.0
            for(i in x.indices){
                val level=abs(x[i])
                val target=if(level>threshold) (threshold+(level-threshold)/ratio)/level.coerceAtLeast(1e-9) else 1.0
                val coeff=if(target<gain)0.18 else 0.006
                gain+=(target-gain)*coeff
                x[i]*=gain
            }
        }
        // Short multi-tap room/plate-style delay. This is a basic effect, not convolution reverb.
        if(s.reverb>0.01){
            val mix=(s.reverb/100.0)*0.42
            val d1=(rate*0.037).toInt().coerceAtLeast(1)
            val d2=(rate*0.061).toInt().coerceAtLeast(1)
            val dry=x.copyOf()
            for(i in x.indices){
                var wet=0.0
                if(i>=d1)wet+=dry[i-d1]*0.48
                if(i>=d2)wet+=dry[i-d2]*0.32
                if(i>=d1*2)wet+=dry[i-d1*2]*0.18
                x[i]=dry[i]*(1.0-mix)+wet*mix
            }
        }
        // Peak guard and a little headroom to avoid hard clipping.
        return ShortArray(x.size){i->(x[i].coerceIn(-0.98,0.98)*32767.0).roundToInt().toShort()}
    }

    private fun biquad(x:DoubleArray,sr:Int,freq:Double,q:Double,gainDb:Double,type:String){
        val a=10.0.pow(gainDb/40.0);val w=2.0*Math.PI*freq.coerceAtMost(sr*0.45)/sr
        val c=cos(w);val sn=sin(w);val alpha=sn/(2.0*q)
        val coeff=when(type){
            "lowshelf"->{val beta=2.0*sqrt(a)*alpha
                listOf(a*((a+1)-(a-1)*c+beta),2*a*((a-1)-(a+1)*c),a*((a+1)-(a-1)*c-beta),(a+1)+(a-1)*c+beta,-2*((a-1)+(a+1)*c),(a+1)+(a-1)*c-beta)}
            "highshelf"->{val beta=2.0*sqrt(a)*alpha
                listOf(a*((a+1)+(a-1)*c+beta),-2*a*((a-1)+(a+1)*c),a*((a+1)+(a-1)*c-beta),(a+1)-(a-1)*c+beta,2*((a-1)-(a+1)*c),(a+1)-(a-1)*c-beta)}
            else->{listOf(1+alpha*a,-2*c,1-alpha*a,1+alpha/a,-2*c,1-alpha/a)}
        }
        val b0=coeff[0];val b1=coeff[1];val b2=coeff[2];val a0=coeff[3];val a1=coeff[4];val a2=coeff[5]
        val nb0=b0/a0;val nb1=b1/a0;val nb2=b2/a0;val na1=a1/a0;val na2=a2/a0
        var x1=0.0;var x2=0.0;var y1=0.0;var y2=0.0
        for(i in x.indices){val v=nb0*x[i]+nb1*x1+nb2*x2-na1*y1-na2*y2;x2=x1;x1=x[i];y2=y1;y1=v;x[i]=v}
    }

    private fun estimate(frame:ShortArray,rate:Int):Double{
        val x=DoubleArray(frame.size/2);var mean=0.0
        for(i in x.indices){x[i]=frame[i*2].toDouble();mean+=x[i]};mean/=x.size
        for(i in x.indices)x[i]-=mean
        val minLag=(rate/2/700).coerceAtLeast(8);val maxLag=minOf(rate/2/75,x.size/2)
        var bestLag=0;var best=0.0
        for(lag in minLag..maxLag){
            var dot=0.0;var a=0.0;var b=0.0
            for(i in 0 until x.size-lag){val p=x[i];val q=x[i+lag];dot+=p*q;a+=p*p;b+=q*q}
            val score=if(a>0&&b>0)dot/sqrt(a*b)else 0.0
            if(score>best){best=score;bestLag=lag}
        }
        return if(bestLag>0&&best>=0.62)(rate/2.0)/bestLag else 0.0
    }
    private fun nearest(midi:Double,key:Int,notes:IntArray):Double{
        val base=floor(midi).toInt();var chosen=base.toDouble();var distance=Double.MAX_VALUE
        for(candidate in base-2..base+2){val pc=((candidate-key)%12+12)%12;if(notes.contains(pc)){val d=abs(candidate-midi);if(d<distance){distance=d;chosen=candidate.toDouble()}}}
        return chosen
    }
    private fun writeWav(file:File,samples:ShortArray,rate:Int){
        file.outputStream().buffered().use{out->
            fun u16(v:Int){out.write(v and 255);out.write((v ushr 8) and 255)}
            fun u32(v:Int){repeat(4){out.write((v ushr (8*it)) and 255)}}
            out.write("RIFF".toByteArray());u32(36+samples.size*2);out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray());u32(16);u16(1);u16(1);u32(rate);u32(rate*2);u16(2);u16(16)
            out.write("data".toByteArray());u32(samples.size*2);samples.forEach{u16(it.toInt() and 0xffff)}
        }
    }
    private fun readMonoWav(file:File):Pair<Int,ShortArray>{
        RandomAccessFile(file,"r").use{raf->
            if(raf.length()<44||readAscii(raf,0,4)!="RIFF"||readAscii(raf,8,4)!="WAVE")error("Only standard WAV recordings are supported.")
            var offset=12L;var dataOffset=-1L;var dataSize=0L;var rate=0;var channels=0;var bits=0;var format=0
            while(offset+8<=raf.length()){
                val id=readAscii(raf,offset,4);val size=readU32(raf,offset+4);val body=offset+8
                when(id){"fmt "->{raf.seek(body);format=readU16(raf);channels=readU16(raf);rate=readU32(raf,body+4).toInt();raf.seek(body+14);bits=readU16(raf)}
                    "data"->{dataOffset=body;dataSize=size;break}}
                offset=body+size+(size and 1L)
            }
            if(format!=1||channels!=1||bits!=16||dataOffset<0)error("Key estimation supports mono 16-bit WAV recordings only.")
            val n=minOf(dataSize/2,Int.MAX_VALUE.toLong()).toInt();val samples=ShortArray(n);raf.seek(dataOffset)
            for(i in 0 until n){val lo=raf.readUnsignedByte();val hi=raf.readUnsignedByte();samples[i]=((hi shl 8)or lo).toShort()}
            return rate to samples
        }
    }
    private fun readAscii(raf:RandomAccessFile,at:Long,n:Int):String{val b=ByteArray(n);raf.seek(at);raf.readFully(b);return String(b,Charsets.US_ASCII)}
    private fun readU16(raf:RandomAccessFile):Int=raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
    private fun readU32(raf:RandomAccessFile,at:Long):Long{raf.seek(at);return raf.readUnsignedByte().toLong() or (raf.readUnsignedByte().toLong() shl 8) or (raf.readUnsignedByte().toLong() shl 16) or (raf.readUnsignedByte().toLong() shl 24)}
}
