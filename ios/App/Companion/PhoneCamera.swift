import AVFoundation
import CoreMotion
import CoreImage
import SwiftUI
import UIKit

/// Back camera for the companion. The live preview is the native
/// AVCaptureVideoPreviewLayer (aspect-fill, full rate); analysis receives separate
/// low-rate (~4 fps) still frames, mirroring the Android TextureView + YUV split.
final class PhoneCamera: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    @Published private(set) var running = false
    @Published private(set) var authorization: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    @Published private(set) var motionAvailable = false

    /// Called on the main thread with an upright frame, its pose and monotonic capture time.
    var onFrame: ((UIImage, VisionPose?, Int64) -> Void)?
    var onPose: ((VisionPose?) -> Void)?

    private let queue = DispatchQueue(label: "companion.camera")
    private let output = AVCaptureVideoDataOutput()
    private let context = CIContext(options: [.useSoftwareRenderer: false])
    private let motion = CMMotionManager()
    private var configured = false
    private var lastFrameAt: Int64 = 0
    private var sessionId: Int64 = 0
    private var latestPose: VisionPose?
    private let poseLock = NSLock()

    func requestAccess() {
        AVCaptureDevice.requestAccess(for: .video) { _ in
            DispatchQueue.main.async { self.authorization = AVCaptureDevice.authorizationStatus(for: .video) }
        }
    }

    func start() {
        authorization = AVCaptureDevice.authorizationStatus(for: .video)
        guard authorization == .authorized else { return }
        sessionId += 1
        startMotion()
        queue.async {
            if !self.configured { self.configure() }
            guard self.configured, !self.session.isRunning else { return }
            self.session.startRunning()
            let isRunning = self.session.isRunning
            DispatchQueue.main.async { self.running = isRunning }
        }
    }

    func stop() {
        motion.stopDeviceMotionUpdates()
        poseLock.lock(); latestPose = nil; poseLock.unlock()
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
            DispatchQueue.main.async { self.running = false }
        }
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = .high
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            return
        }
        session.addOutput(output)
        session.commitConfiguration()
        configured = true
    }

    /// Phone rotation only gates marker freshness and automatic capture steadiness;
    /// it is never sent anywhere.
    private func startMotion() {
        guard motion.isDeviceMotionAvailable else {
            motionAvailable = false
            return
        }
        motionAvailable = true
        motion.deviceMotionUpdateInterval = 1.0 / 20
        let currentSession = sessionId
        motion.startDeviceMotionUpdates(to: .main) { [weak self] data, _ in
            guard let self = self else { return }
            var pose: VisionPose? = nil
            if let attitude = data?.attitude {
                pose = VisionPose(yawDegrees: attitude.yaw * 180 / .pi, pitchDegrees: attitude.pitch * 180 / .pi,
                                  sessionId: currentSession, rollDegrees: attitude.roll * 180 / .pi)
            }
            self.poseLock.lock(); self.latestPose = pose; self.poseLock.unlock()
            self.onPose?(pose)
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = visionElapsedMs()
        guard now - lastFrameAt >= 250, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastFrameAt = now
        // The sensor delivers landscape buffers; .right makes them portrait like the preview.
        var image = CIImage(cvPixelBuffer: buffer).oriented(.right)
        let longEdge = max(image.extent.width, image.extent.height)
        if longEdge > 960 {
            let scale = 960 / longEdge
            image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }
        guard let cgImage = context.createCGImage(image, from: image.extent) else { return }
        let frame = UIImage(cgImage: cgImage)
        poseLock.lock(); let pose = latestPose; poseLock.unlock()
        DispatchQueue.main.async { self.onFrame?(frame, pose, now) }
    }
}

/// Native full-rate preview, center-cropped (aspect-fill) like the Android recognition preview.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .black
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        if let connection = uiView.previewLayer.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
    }
}
