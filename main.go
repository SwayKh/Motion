package main

import (
	"fmt"
	"path/filepath"
	"strings"

	"VideoProject/ffmpegutils"
)

func getFileNameAndExt(filePath string) (baseName, extension string) {
	filePathBase := filepath.Base(filePath)
	extension = filepath.Ext(filePath)
	baseName = strings.TrimSuffix(filePathBase, extension)

	return baseName, extension
}

func main() {
	var multiplier float64 = 2
	filePath := "testing.mp4"

	filePathName, filePathExt := getFileNameAndExt(filePath)

	output := "output/" + filePathName + fmt.Sprintf("_x%.2f", multiplier) + filePathExt
	err := ffmpegutils.NewProcessor(filePath).Speed(multiplier).VSync(0).Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video Speed changed")

	output = "output/" + filePathName + fmt.Sprintf("_Volume_x%.2f", multiplier) + filePathExt
	err = ffmpegutils.NewProcessor(filePath).Volume(multiplier).Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video Volume changed")

	output = "output/" + filePathName + ".mp3"
	err = ffmpegutils.NewProcessor(filePath).ToAudio().Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video converted to audio")

	output = "output/" + filePathName + "_Flipped" + filePathExt
	err = ffmpegutils.NewProcessor(filePath).Flip(ffmpegutils.Vertical).VideoCodec("libx264").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video flipped vertically")

	output = "output/" + filePathName + "_Trimmed_" + filePathExt
	err = ffmpegutils.NewProcessor(filePath).Trim("00:00:02", "00:00:26").VideoCodec("libx264").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video trimmed")

	fmt.Println("Done")
}
