package zscale

import Chisel._

abstract trait HASTIConstants
{
  val SZ_HTRANS     = 2
  val HTRANS_IDLE   = UInt(0, SZ_HTRANS)
  val HTRANS_BUSY   = UInt(1, SZ_HTRANS)
  val HTRANS_NONSEQ = UInt(2, SZ_HTRANS)
  val HTRANS_SEQ    = UInt(3, SZ_HTRANS)

  val SZ_HBURST     = 3
  val HBURST_SINGLE = UInt(0, SZ_HBURST)
  val HBURST_INCR   = UInt(1, SZ_HBURST)
  val HBURST_WRAP4  = UInt(2, SZ_HBURST)
  val HBURST_INCR4  = UInt(3, SZ_HBURST)
  val HBURST_WRAP8  = UInt(4, SZ_HBURST)
  val HBURST_INCR8  = UInt(5, SZ_HBURST)
  val HBURST_WRAP16 = UInt(6, SZ_HBURST)
  val HBURST_INCR16 = UInt(7, SZ_HBURST)

  val SZ_HRESP      = 1
  val HRESP_OKAY    = UInt(0, SZ_HRESP)
  val HRESP_ERROR   = UInt(1, SZ_HRESP)

  val SZ_HSIZE = 3
  val SZ_HPROT = 4

  // TODO: Parameterize
  val SZ_HADDR = 32
  val SZ_HDATA = 32

  def dgate(valid: Bool, b: Bits) = Fill(b.getWidth, valid) & b
}

class HASTIMasterIO extends Bundle
{
  val haddr     = UInt(OUTPUT, SZ_HADDR)
  val hwrite    = Bool(OUTPUT)
  val hsize     = UInt(OUTPUT, SZ_HSIZE)
  val hburst    = UInt(OUTPUT, SZ_HBURST)
  val hprot     = UInt(OUTPUT, SZ_HPROT)
  val htrans    = UInt(OUTPUT, SZ_HTRANS)
  val hmastlock = Bool(OUTPUT)

  val hwdata = Bits(OUTPUT, SZ_HDATA)
  val hrdata = Bits(INPUT, SZ_HDATA)

  val hready = Bool(INPUT)
  val hresp  = UInt(INPUT, SZ_HRESP)
}

class HASTISlaveIO extends Bundle
{
  val haddr     = UInt(INPUT, SZ_HADDR)
  val hwrite    = Bool(INPUT)
  val hsize     = UInt(INPUT, SZ_HSIZE)
  val hburst    = UInt(INPUT, SZ_HBURST)
  val hprot     = UInt(INPUT, SZ_HPROT)
  val htrans    = UInt(INPUT, SZ_HTRANS)
  val hmastlock = Bool(INPUT)

  val hwdata = Bits(INPUT, SZ_HDATA)
  val hrdata = Bits(OUTPUT, SZ_HDATA)

  val hsel      = Bool(INPUT)
  val hreadyin  = Bool(INPUT)
  val hreadyout = Bool(OUTPUT)
  val hresp     = UInt(OUTPUT, SZ_HRESP)
}

class HASTIBus(amap: Seq[UInt=>Bool]) extends Module
{
  val io = new Bundle {
    val master = new HASTIMasterIO().flip
    val slaves = Vec.fill(amap.size){new HASTISlaveIO}.flip
  }

  // skid buffer
  val skb_valid = Reg(init = Bool(false))
  val skb_haddr = Reg(UInt(width = SZ_HADDR))
  val skb_hwrite = Reg(Bool())
  val skb_hsize = Reg(UInt(width = SZ_HSIZE))
  val skb_hburst = Reg(UInt(width = SZ_HBURST))
  val skb_hprot = Reg(UInt(width = SZ_HPROT))
  val skb_htrans = Reg(UInt(width = SZ_HTRANS))
  val skb_hmastlock = Reg(Bool())
  val skb_hwdata = Reg(UInt(width = SZ_HDATA))

  val master_haddr = Mux(skb_valid, skb_haddr, io.master.haddr)
  val master_hwrite = Mux(skb_valid, skb_hwrite, io.master.hwrite)
  val master_hsize = Mux(skb_valid, skb_hsize, io.master.hsize)
  val master_hburst = Mux(skb_valid, skb_hburst, io.master.hburst)
  val master_hprot = Mux(skb_valid, skb_hprot, io.master.hprot)
  val master_htrans = Mux(skb_valid, skb_htrans, io.master.htrans)
  val master_hmastlock = Mux(skb_valid, skb_hmastlock, io.master.hmastlock)
  val master_hwdata = Mux(skb_valid, skb_hwdata, io.master.hwdata)

  val hsels = PriorityEncoderOH(
    (io.slaves zip amap) map { case (s, afn) => {
      s.haddr := master_haddr
      s.hwrite := master_hwrite
      s.hsize := master_hsize
      s.hburst := master_hburst
      s.hprot := master_hprot
      s.htrans := master_htrans
      s.hmastlock := master_hmastlock
      s.hwdata := master_hwdata
      afn(master_haddr) && master_htrans.orR
    }})

  (io.slaves zip hsels) foreach { case (s, hsel) => {
    s.hsel := hsel
    s.hreadyin := skb_valid || io.master.hready
  } }

  val skid = (hsels zip io.slaves.map(_.hreadyout)).map{ case (s, r) => s && !r }.reduce(_||_)

  skb_valid := skid

  when (skid) {
    skb_haddr := io.master.haddr
    skb_hwrite := io.master.hwrite
    skb_hsize := io.master.hsize
    skb_hburst := io.master.hburst
    skb_hprot := io.master.hprot
    skb_htrans := io.master.htrans
    skb_hmastlock := io.master.hmastlock
  }

  val s1_hsels = Vec.fill(amap.size){Reg(init = Bool(false))}

  (s1_hsels zip hsels) foreach { case (s1, s) =>
    when (!skid) { s1 := s }
  }

  val default_hready = s1_hsels.reduce(_||_) === Bool(false)
  val slave_hready = Mux1H(s1_hsels, io.slaves.map(_.hreadyout))
  io.master.hready := !skb_valid && (default_hready || slave_hready)
  io.master.hrdata := Mux1H(s1_hsels, io.slaves.map(_.hrdata))
  io.master.hresp := Mux1H(s1_hsels, io.slaves.map(_.hresp))
}

class HASTISlaveMux(n: Int) extends Module
{
  val io = new Bundle {
    val ins = Vec.fill(n){new HASTISlaveIO}
    val out = new HASTISlaveIO().flip
  }

  // skid buffers
  val skb_valid = Vec.fill(n){Reg(init = Bool(false))}
  val skb_haddr = Vec.fill(n){Reg(UInt(width = SZ_HADDR))}
  val skb_hwrite = Vec.fill(n){Reg(Bool())}
  val skb_hsize = Vec.fill(n){Reg(UInt(width = SZ_HSIZE))}
  val skb_hburst = Vec.fill(n){Reg(UInt(width = SZ_HBURST))}
  val skb_hprot = Vec.fill(n){Reg(UInt(width = SZ_HPROT))}
  val skb_htrans = Vec.fill(n){Reg(UInt(width = SZ_HTRANS))}
  val skb_hmastlock = Vec.fill(n){Reg(Bool())}

  val requests = (io.ins zip skb_valid) map { case (in, v) => in.hsel && in.hreadyin || v }
  val grants = PriorityEncoderOH(requests)

  val s1_grants = Vec.fill(n){Reg(init = Bool(true))}

  (s1_grants zip grants) foreach { case (g1, g) =>
    when (io.out.hreadyout) { g1 := g }
  }

  def sel[T <: Data](in: Vec[T], s1: Vec[T]) =
    Vec((skb_valid zip s1 zip in) map { case ((v, s), in) => Mux(v, s, in) })

  io.out.haddr := Mux1H(grants, sel(Vec(io.ins.map(_.haddr)), skb_haddr))
  io.out.hwrite := Mux1H(grants, sel(Vec(io.ins.map(_.hwrite)), skb_hwrite))
  io.out.hsize := Mux1H(grants, sel(Vec(io.ins.map(_.hsize)), skb_hsize))
  io.out.hburst := Mux1H(grants, sel(Vec(io.ins.map(_.hburst)), skb_hburst))
  io.out.hprot := Mux1H(grants, sel(Vec(io.ins.map(_.hprot)), skb_hprot))
  io.out.htrans := Mux1H(grants, sel(Vec(io.ins.map(_.htrans)), skb_htrans))
  io.out.hmastlock := Mux1H(grants, sel(Vec(io.ins.map(_.hmastlock)), skb_hmastlock))
  io.out.hsel := (grants zip s1_grants).map{ case (g, g1) => g && g1 }.reduce(_||_)

  (io.ins zipWithIndex) map { case (in, i) => {
    val g = grants(i)
    val g1 = s1_grants(i)
    when (io.out.hreadyout && g) {
      skb_valid(i) := Bool(false)
    }
    when (io.out.hreadyout && g1 && !g) {
      skb_valid(i) := in.hsel && in.hreadyin
      skb_haddr(i) := in.haddr
      skb_hwrite(i) := in.hwrite
      skb_hsize(i) := in.hsize
      skb_hburst(i) := in.hburst
      skb_hprot(i) := in.hprot
      skb_htrans(i) := in.htrans
      skb_hmastlock(i) := in.hmastlock
    }
  } }

  io.out.hwdata := Mux1H(s1_grants, io.ins.map(_.hwdata))
  io.out.hreadyin := Mux1H(s1_grants, io.ins.map(_.hreadyin))

  (io.ins zipWithIndex) foreach { case (in, i) => {
    val g1 = s1_grants(i)
    in.hrdata := dgate(g1, io.out.hrdata)
    in.hreadyout := io.out.hreadyout && g1
    in.hresp := dgate(g1, io.out.hresp)
  } }
}

class HASTIXbar(n: Int, amap: Seq[UInt=>Bool]) extends Module
{
  val io = new Bundle {
    val masters = Vec.fill(n){new HASTIMasterIO}.flip
    val slaves = Vec.fill(amap.size){new HASTISlaveIO}.flip
  }

  val buses = List.fill(n){Module(new HASTIBus(amap))}
  val muxes = List.fill(amap.size){Module(new HASTISlaveMux(n))}

  (buses.map(b => b.io.master) zip io.masters) foreach { case (b, m) => b <> m }
  (muxes.map(m => m.io.out)    zip io.slaves ) foreach { case (x, s) => x <> s }
  for (m <- 0 until n; s <- 0 until amap.size) yield {
    buses(m).io.slaves(s) <> muxes(s).io.ins(m)
  }
}

class HASTISlaveToMaster extends Module
{
  val io = new Bundle {
    val in = new HASTISlaveIO
    val out = new HASTIMasterIO
  }

  io.out.haddr := io.in.haddr
  io.out.hwrite := io.in.hwrite
  io.out.hsize := io.in.hsize
  io.out.hburst := io.in.hburst
  io.out.hprot := io.in.hprot
  io.out.htrans := Mux(io.in.hsel && io.in.hreadyin, io.in.htrans, HTRANS_IDLE)
  io.out.hmastlock := io.in.hmastlock
  io.out.hwdata := io.in.hwdata
  io.in.hrdata := io.out.hrdata
  io.in.hreadyout := io.out.hready
  io.in.hresp := io.out.hresp
}
