# Text to speech
This code generates speech from text for en-US and es-US. 
The project is a simplified JAVA version of this [project](https://github.com/kazunori279/pdf2audiobook) by [Kaz Sato](https://github.com/kazunori279/pdf2audiobook).

The code does simple text to speech generating multiple mp3 files accounting for the 5K character limit of the Speechify API. Due to the poor mp3 handling support in JAVA, a separate cloud function will be added using Python to merge the outputed mp3s.





