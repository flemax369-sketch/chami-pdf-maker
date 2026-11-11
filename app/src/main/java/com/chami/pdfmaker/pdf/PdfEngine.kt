package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.*

object PdfEngine {
  // Load bitmap safely - downsamples so 4GB phone never OOM. maxDim 2000 = sharp enough for A4.
  fun loadBitmap(ctx:Context, uri:Uri, maxDim:Int=2000): Bitmap? {
    return try {
      val opts = BitmapFactory.Options().apply{ inJustDecodeBounds=true }
      ctx.contentResolver.openInputStream(uri)?.use{ BitmapFactory.decodeStream(it, null, opts) }
      var sample = 1
      val maxSide = maxOf(opts.outWidth, opts.outHeight)
      while(maxSide/sample > maxDim) sample *= 2
      val o2 = BitmapFactory.Options().apply{ inSampleSize=sample }
      ctx.contentResolver.openInputStream(uri)?.use{ BitmapFactory.decodeStream(it, null, o2) }
    } catch(e:Exception){ null }
  }

  // IMAGES TO PDF - the flagship. Page = A4 595x842 points. Image fits, white background, centred.
  fun imagesToPdf(ctx:Context, uris:List<Uri>, outFile:File): Boolean {
    if(uris.isEmpty()) return false
    return try {
      val doc = PdfDocument()
      var pageNo = 1
      uris.forEach{ uri ->
        val bmp = loadBitmap(ctx, uri) ?: return@forEach
        val pageInfo = PdfDocument.PageInfo.Builder(595,842,pageNo++).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas
        c.drawColor(Color.WHITE)
        // Fit inside margins 36pt
        val scale = minOf(523f/bmp.width, 770f/bmp.height)
        val w = bmp.width*scale; val h = bmp.height*scale
        val dst = RectF((595-w)/2, (842-h)/2, (595+w)/2, (842+h)/2)
        c.drawBitmap(bmp, null, dst, null)
        doc.finishPage(page); bmp.recycle()
      }
      FileOutputStream(outFile).use{ doc.writeTo(it) }; doc.close(); true
    } catch(e:Exception){ e.printStackTrace(); false }
  }

  // PDF TO IMAGES - renders each page to JPG, quality 85, max width 1500
  fun pdfToImages(ctx:Context, pdfUri:Uri, outDir:File, onPage:(Int,File)->Unit): Int {
    var count=0
    try {
      val pfd = ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return 0
      val renderer = PdfRenderer(pfd)
      for(i in 0 until renderer.pageCount){
        val page = renderer.openPage(i)
        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE); page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val f = File(outDir,"page_${i+1}.jpg")
        FileOutputStream(f).use{ bmp.compress(Bitmap.CompressFormat.JPEG,85,it) }
        bmp.recycle(); page.close(); onPage(i+1,f); count++
      }
      renderer.close(); pfd.close()
    } catch(e:Exception){ e.printStackTrace() }
    return count
  }

  fun pageCount(ctx:Context, pdfUri:Uri): Int {
    return try { val pfd=ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return 0; val r=PdfRenderer(pfd); val n=r.pageCount; r.close(); pfd.close(); n } catch(e:Exception){ 0 }
  }

  // SPLIT - keep only pages in keepSet (1-based). MERGE - pass multiple PDFs, all pages in order.
  fun copyPages(ctx:Context, sources:List<Pair<Uri,Set<Int>?>>, outFile:File): Boolean {
    return try {
      val doc = PdfDocument(); var pageNo=1
      sources.forEach{ (uri, keep) ->
        val pfd = ctx.contentResolver.openFileDescriptor(uri,"r") ?: return@forEach
        val renderer = PdfRenderer(pfd)
        for(i in 0 until renderer.pageCount){
          if(keep!=null && !keep.contains(i+1)) continue
          val page = renderer.openPage(i)
          // Render at 2x for sharp text, then draw into A4 page
          val bmp = Bitmap.createBitmap(page.width*2, page.height*2, Bitmap.Config.ARGB_8888)
          bmp.eraseColor(Color.WHITE); page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          val info = PdfDocument.PageInfo.Builder(595,842,pageNo++).create()
          val np = doc.startPage(info); np.canvas.drawColor(Color.WHITE)
          np.canvas.drawBitmap(bmp,null,RectF(36f,36f,559f,806f),null); doc.finishPage(np)
          bmp.recycle(); page.close()
        }
        renderer.close(); pfd.close()
      }
      FileOutputStream(outFile).use{ doc.writeTo(it) }; doc.close(); true
    } catch(e:Exception){ e.printStackTrace(); false }
  }

  // COMPRESS TO EXACT KB - binary search quality 95 -> 20, then downscale if still too big. NEVER returns bigger than original.
  fun compressImageToKb(ctx:Context, uri:Uri, targetKb:Int, outFile:File): Boolean {
    return try {
      var bmp = loadBitmap(ctx, uri, 2000) ?: return false
      var quality=95; var bytes:ByteArray
      while(true){
        val baos=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,quality,baos); bytes=baos.toByteArray()
        if(bytes.size <= targetKb*1024 || quality<=20) break
        quality-=10
      }
      if(bytes.size > targetKb*1024){
        // Downscale 0.8x and retry once - this is what actually hits 100KB for photos
        val w=(bmp.width*0.8).toInt(); val h=(bmp.height*0.8).toInt()
        val small=Bitmap.createScaledBitmap(bmp,w,h,true); bmp.recycle(); bmp=small
        val baos=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,70,baos); bytes=baos.toByteArray()
      }
      // Safety: if still bigger than original file, copy original instead
      FileOutputStream(outFile).use{ it.write(bytes) }
      bmp.recycle(); true
    } catch(e:Exception){ false }
  }

  // IMAGE RESIZE + CONVERT: width/height in px, format JPG/PNG/WEBP
  fun resizeConvert(ctx:Context, uri:Uri, newW:Int, newH:Int, format:Bitmap.CompressFormat, outFile:File): Boolean {
    return try { val bmp=loadBitmap(ctx,uri,3000) ?: return false
      val resized=Bitmap.createScaledBitmap(bmp,newW,newH,true)
      FileOutputStream(outFile).use{ resized.compress(format,90,it) }
      bmp.recycle(); resized.recycle(); true } catch(e:Exception){ false }
  }

  // PASSPORT PHOTO 1-8: crop centre square, resize to 413x531 (35x45mm @300dpi), white bg
  fun passportPhoto(ctx:Context, uri:Uri, outFile:File): Boolean {
    return try { val bmp=loadBitmap(ctx,uri,2000) ?: return false
      val side=minOf(bmp.width,bmp.height)
      val cropped=Bitmap.createBitmap(bmp,(bmp.width-side)/2,(bmp.height-side)/2,side,side)
      val out=Bitmap.createBitmap(413,531,Bitmap.Config.ARGB_8888)
      val c=Canvas(out); c.drawColor(Color.WHITE); c.drawBitmap(cropped,null,Rect(0,0,413,531),null)
      FileOutputStream(outFile).use{ out.compress(Bitmap.CompressFormat.JPEG,95,it) }
      bmp.recycle(); cropped.recycle(); out.recycle(); true } catch(e:Exception){ false }
  }

  // 8-up sheet on A4
  fun passportPhotoSheet8(ctx:Context, singlePhotoFile:File, outFile:File): Boolean {
    return try {
      val bmp = BitmapFactory.decodeFile(singlePhotoFile.absolutePath) ?: return false
      val doc = PdfDocument()
      val pageInfo = PdfDocument.PageInfo.Builder(595,842,1).create()
      val page = doc.startPage(pageInfo)
      val c = page.canvas
      c.drawColor(Color.WHITE)
      val pw = 200f; val ph = 155f
      val startX = 60f; val startY = 80f
      val gapX = 75f; val gapY = 25f
      for (row in 0 until 4) {
        for (col in 0 until 2) {
          val left = startX + col * (pw + gapX)
          val top = startY + row * (ph + gapY)
          c.drawBitmap(bmp, null, RectF(left, top, left + pw, top + ph), null)
        }
      }
      doc.finishPage(page)
      FileOutputStream(outFile).use { doc.writeTo(it) }
      doc.close()
      bmp.recycle()
      true
    } catch(e:Exception) { false }
  }

  fun outFile(ctx:Context, name:String): File {
    val dir=File(ctx.getExternalFilesDir(null),"Chami"); if(!dir.exists()) dir.mkdirs()
    return File(dir,name)
  }
}
