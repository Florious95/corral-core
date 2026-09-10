package dev.agentmirror.app.termview
import java.lang.management.ManagementFactory
object GeometryBench {
    @Volatile private var sink = 0L
    @JvmStatic fun main(args:Array<String>) {
        val cps=(0x2500..0x259F).filter(BoxBlockGeometry::handles).toIntArray()
        val cache=BoxBlockGeometryCache()
        val bean=ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        fun run(fast:Boolean, rounds:Int):Long {
            var value=0L
            repeat(rounds) {
                for(cp in cps) {
                    val corner:BoxBlockGeometry.RoundedCorner?
                    val fills:List<BoxBlockGeometry.Fill>
                    if(fast){val p=cache.get(cp,19,37);corner=p.corner;fills=p.fills}
                    else {corner=BoxBlockGeometry.roundedCorner(cp,0,0,19,37);fills=if(corner==null)BoxBlockGeometry.fills(cp,0,0,19,37)else emptyList()}
                    if(corner!=null)value+=corner.radius.toRawBits().toLong()
                    for(i in fills.indices){val f=fills[i];value+=f.rect.left+f.rect.top+f.rect.right+f.rect.bottom+f.alpha}
                }
            }
            sink=value;return value
        }
        repeat(5){check(run(false,200)==run(true,200))}
        for(fast in listOf(false,true,true,false)) {
            val before=bean.getThreadAllocatedBytes(Thread.currentThread().id)
            val start=System.nanoTime();val checksum=run(fast,2000);val elapsed=System.nanoTime()-start
            val bytes=bean.getThreadAllocatedBytes(Thread.currentThread().id)-before
            println("cached=$fast glyphs=${cps.size*2000} allocated_bytes=$bytes elapsed_ns=$elapsed checksum=$checksum")
        }
    }
}
