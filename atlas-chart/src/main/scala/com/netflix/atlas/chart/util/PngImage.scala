/*
 * Copyright 2014-2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.chart.util

import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.AttributedString

import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataFormatImpl
import javax.imageio.metadata.IIOMetadataNode

object PngImage {

  // Disable using on-disk cache for images. Avoids temp files on shared services.
  ImageIO.setUseCache(false)

  def apply(bytes: Array[Byte]): PngImage = {
    val input = new ByteArrayInputStream(bytes)
    apply(input)
  }

  def apply(input: InputStream): PngImage = {
    try {
      val iterator = ImageIO.getImageReadersBySuffix("png")
      require(iterator.hasNext, "no image readers for png")

      val reader = iterator.next()
      reader.setInput(ImageIO.createImageInputStream(input), true)

      val index = 0
      val metadata = reader.getImageMetadata(index)
      val fields = extractTxtFields(metadata)

      val image = reader.read(index)

      PngImage(image, fields)
    } finally {
      input.close()
    }
  }

  private def extractTxtFields(m: IIOMetadata): Map[String, String] = {
    val elements = m
      .getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName)
      .asInstanceOf[IIOMetadataNode]
      .getElementsByTagName("TextEntry")
    (0 until elements.getLength)
      .map(i => elements.item(i).asInstanceOf[IIOMetadataNode])
      .map(m => m.getAttribute("keyword") -> m.getAttribute("value"))
      .toMap
  }

  def userError(imgText: String, width: Int, height: Int): PngImage = {
    val userErrorYellow = new Color(0xFF, 0xCF, 0x00)
    error(imgText, width, height, "USER ERROR:", Color.BLACK, userErrorYellow)
  }

  def systemError(imgText: String, width: Int, height: Int): PngImage = {
    val systemErrorRed = new Color(0xF8, 0x20, 0x00)
    error(imgText, width, height, "SYSTEM ERROR:", Color.WHITE, systemErrorRed)
  }

  def error(
    imgText: String,
    width: Int,
    height: Int,
    imgTextPrefix: String = "ERROR:",
    imgTextColor: Color = Color.WHITE,
    imgBackgroundColor: Color = Color.BLACK
  ): PngImage = {
    val fullMsg = s"$imgTextPrefix $imgText"

    val image = newBufferedImage(width, height)
    val g = image.createGraphics

    // Try to avoid problems with different default fonts on various platforms. Java will use the
    // "Dialog" font by default which can get mapped differently on various systems. It looks like
    // passing a bad font name into the font constructor will just silently fall back to the
    // default so it should still function if this font isn't present. However, the lucida font
    // was chosen as it is expected to be widely available:
    // https://docs.oracle.com/javase/tutorial/2d/text/fonts.html
    val font = new Font("Lucida Sans Regular", Font.PLAIN, 12)
    g.setFont(font)

    g.setPaint(imgBackgroundColor)
    g.fill(new Rectangle(0, 0, width, height))

    g.setPaint(imgTextColor)
    val attrStr = new AttributedString(fullMsg)
    attrStr.addAttribute(TextAttribute.FONT, font)
    val iterator = attrStr.getIterator
    val measurer = new LineBreakMeasurer(iterator, g.getFontRenderContext)

    val wrap = width - 8.0f
    var y = 0.0f
    while (measurer.getPosition < fullMsg.length) {
      val layout = measurer.nextLayout(wrap)
      y += layout.getAscent
      layout.draw(g, 4.0f, y)
      y += layout.getDescent + layout.getLeading
    }

    PngImage(image, Map.empty)
  }

  def diff(img1: RenderedImage, img2: RenderedImage): PngImage = {
    val bi1 = newBufferedImage(img1)
    val bi2 = newBufferedImage(img2)

    val dw = List(img1.getWidth, img2.getWidth).max
    val dh = List(img1.getHeight, img2.getHeight).max

    val diffImg = newBufferedImage(dw, dh)
    val g = diffImg.createGraphics
    g.setPaint(Color.BLACK)
    g.fill(new Rectangle(0, 0, dw, dh))

    val red = Color.RED.getRGB

    var count = 0
    var x = 0
    while (x < dw) {
      var y = 0
      while (y < dh) {
        if (contains(bi1, x, y) && contains(bi2, x, y)) {
          val c1 = bi1.getRGB(x, y)
          val c2 = bi2.getRGB(x, y)
          if (c1 != c2) {
            diffImg.setRGB(x, y, red)
            count += 1
          }
        } else {
          diffImg.setRGB(x, y, red)
          count += 1
        }
        y += 1
      }
      x += 1
    }

    val identical = (count == 0).toString
    val diffCount = count.toString
    val meta = Map("identical" -> identical, "diff-pixel-count" -> diffCount)
    PngImage(diffImg, meta)
  }

  private def contains(img: RenderedImage, x: Int, y: Int): Boolean = {
    x < img.getWidth && y < img.getHeight
  }

  private def newBufferedImage(w: Int, h: Int): BufferedImage = {
    new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  }

  private def newBufferedImage(img: RenderedImage): BufferedImage = {
    img match {
      case bi: BufferedImage => bi
      case _ =>
        val w = img.getWidth
        val h = img.getHeight
        val bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = bi.createGraphics
        g.drawRenderedImage(img, new AffineTransform)
        bi
    }
  }
}

case class PngImage(data: RenderedImage, metadata: Map[String, String] = Map.empty) {

  type JList = java.util.List[String]

  def toByteArray: Array[Byte] = {
    val buffer = new ByteArrayOutputStream
    write(buffer)
    buffer.toByteArray
  }

  def write(output: OutputStream): Unit = {
    try {
      val iterator = ImageIO.getImageWritersBySuffix("png")
      require(iterator.hasNext, "no image writers for png")

      val writer = iterator.next()
      writer.setOutput(ImageIO.createImageOutputStream(output))

      val pngMeta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(data), null)
      metadata.foreach { t =>
        val textEntry = new IIOMetadataNode("TextEntry")
        textEntry.setAttribute("keyword", t._1)
        textEntry.setAttribute("value", t._2)
        textEntry.setAttribute("compression", if (t._2.length > 100) "zip" else "none")

        val text = new IIOMetadataNode("Text")
        text.appendChild(textEntry)

        val root = new IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName)
        root.appendChild(text)

        pngMeta.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName, root)
      }

      val iioImage = new IIOImage(data, null, pngMeta)
      writer.write(null, iioImage, null)
    } finally {
      output.close()
    }
  }
}
