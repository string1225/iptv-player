These short MPEG-1 Layer II fixtures contain generated sine waves, not TV recordings.
They were generated with FFmpeg 7.1. Each file is approximately 0.2 seconds long.

ffmpeg -f lavfi -i sine=frequency=997:sample_rate=48000:duration=0.2 -ac 2 -c:a mp2 -b:a 256k mp2-48000-stereo.mp2
ffmpeg -f lavfi -i sine=frequency=523:sample_rate=44100:duration=0.2 -ac 1 -c:a mp2 -b:a 128k mp2-44100-mono.mp2
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=32000:duration=0.2 -ac 2 -c:a mp2 -b:a 192k mp2-32000-stereo.mp2
