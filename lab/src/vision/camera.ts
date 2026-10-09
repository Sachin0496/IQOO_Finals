/** A browser may pause a background, video-only element; that is not a failure, playback resumes when visible. */
async function play(video: HTMLVideoElement): Promise<void> {
  try {
    await video.play()
  } catch (e) {
    if (!(e instanceof DOMException && e.name === 'AbortError')) throw e
  }
}

/** Front camera at 640x480, 30 fps where the device allows it. */
export async function openCamera(video: HTMLVideoElement): Promise<MediaStream> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: false,
    video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 30 } },
  })
  video.srcObject = stream
  video.muted = true
  video.playsInline = true
  await play(video)
  return stream
}

/** A recorded video as the source, for testing on recorded faces. Plays muted, on a loop. */
export async function openFile(video: HTMLVideoElement, file: File): Promise<void> {
  video.srcObject = null
  video.src = URL.createObjectURL(file)
  video.muted = true
  video.loop = true
  video.playsInline = true
  await play(video)
}
