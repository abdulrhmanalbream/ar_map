import UIKit

struct EncodedVisionImage {
    let preview: UIImage
    let base64: String
    let signature: VisionFrameSignature?
    var width: Int { Int(preview.size.width * preview.scale) }
    var height: Int { Int(preview.size.height * preview.scale) }
}

struct VisionEncodingFailure: Error {}

/// 16×12 luminance grid. The image is area-averaged into the grid (Android point-sampled);
/// both frames of a comparison use the same method, which is what the thresholds need.
func visionFrameSignature(_ image: UIImage) -> VisionFrameSignature? {
    guard let cgImage = upright(image).cgImage else { return nil }
    let width = 16, height = 12
    var pixels = [UInt8](repeating: 0, count: width * height * 4)
    let drawn: Bool = pixels.withUnsafeMutableBytes { buffer -> Bool in
        guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.interpolationQuality = .medium
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    guard drawn else { return nil }
    var luma: [Int] = []
    luma.reserveCapacity(192)
    for i in 0..<(width * height) {
        let r = Int(pixels[i * 4]), g = Int(pixels[i * 4 + 1]), b = Int(pixels[i * 4 + 2])
        luma.append((r * 77 + g * 150 + b * 29) >> 8)
    }
    return VisionFrameSignature(luma: luma)
}

/// Bakes EXIF orientation into pixels so boxes refer to the image the user sees.
private func upright(_ image: UIImage) -> UIImage {
    if image.imageOrientation == .up && image.scale == 1 { return image }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let size = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
    return UIGraphicsImageRenderer(size: size, format: format).image { _ in
        image.draw(in: CGRect(origin: .zero, size: size))
    }
}

/// Only memory-resident, bounded JPEGs: long edge ≤ 960 px, ≤ 400 KiB, as the server requires.
/// Call off the main thread.
func encodeVisionImage(_ source: UIImage) throws -> EncodedVisionImage {
    let image = upright(source)
    let sourceWidth = image.size.width, sourceHeight = image.size.height
    guard sourceWidth > 0, sourceHeight > 0 else { throw VisionEncodingFailure() }
    let signature = visionFrameSignature(image)
    var longEdge = min(960, max(sourceWidth, sourceHeight))
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    while longEdge >= 1 {
        let scale = longEdge / max(sourceWidth, sourceHeight)
        let size = CGSize(width: max(1, (sourceWidth * scale).rounded()), height: max(1, (sourceHeight * scale).rounded()))
        let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        for quality in [0.78, 0.65, 0.50, 0.38] as [CGFloat] {
            if let data = scaled.jpegData(compressionQuality: quality), data.count <= 400 * 1024 {
                return EncodedVisionImage(preview: scaled, base64: data.base64EncodedString(), signature: signature)
            }
        }
        if longEdge <= 320 { throw VisionEncodingFailure() }
        longEdge = max(320, (longEdge * 0.75).rounded())
    }
    throw VisionEncodingFailure()
}
