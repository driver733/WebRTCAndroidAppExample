ffmpeg -video_size 384x288 -pixel_format yuv420p -i ~/Downloads/example.y4m -vf format=yuv420p ~/Downloads/example_conv.y4m
ffmpeg -i ~/Downloads/example.y4m ~/Downloads/example_conv.yuv
ffmpeg -i ~/Downloads/example.y4m -vf format=yuv420p ~/Downloads/example_conv.y4m