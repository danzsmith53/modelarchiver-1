# model-archive-format

##What's new
This is the initial release of the `model-archive-format` repo.

##Known issues
None.

##Overview
Model ARchive (MAR) is a format for storing a model along with code that will load it and present it for scoring.
It makes models portable between applications that do not understand a given model's details or its implementation.

This allows models from different modeling tools and having varied APIs to be enclosed inside a common wrapper and
exposed using consistent APIs.

This approach is different than that of PMML/PFA or other techniques that try to describe the details of the model
in a universal language to be interpreted by compatible environments.  As such, MAR has different advantages and
disadvantages, but provides another option.

The aspect of MAR that enables universal compatibility is the "tapanalyticstoolkit.scoring-interfaces".  Models that
implement this interface can use MAR.  It is a very simple API that defines a "score" method along with a
few other methods that describe the inputs and outputs of that score method, for the given model:

    def score(record: Array[Any]): Array[Any]
    def input(): Array[Field]
    def output(): Array[Field]
    def metadata(): ModelMetaData

For Example, consider a PCA model:

+ The PCA model could define a `score` method that takes as a floating point number and then returns that number
along with its calculated principal components and a t-squared index.
+ The `input` method would describe the input record as an array of a single floating point number.
+ The `output` would describe an array containing (1) a list of floating point numbers representing the principal
components and (2) the t-squared index (a floating point number).  It is implied that the original record fields are the
first elements returned.  So, in this case, the PCA score method returns an array of 3 elements: the one floating point
number input, and then the list, and the other floating point number.
+ The `metadata` method could describe parameters and training information that might be interesting to the client,
for example, that a PCA Model was trained with mean-center data and whether a 'k' value was provided.  `metadata` is mostly
free-form.

These methods allow apps and services to consume an externally trained model.  When dealing with model creation
frameworks that do *not* use this particular Model interface (that is, most frameworks),  an *adaptor* can be written to
present these four methods.

The final aspect of MAR is the ModelReader, also from the "tapanalyticstoolkit.scoring-interfaces".  This reader has the
responsibility of loading a model into memory from a MAR zip file.  The MAR zip file must contain all the code
dependencies require to load the model and execute the methods described above.

It follows that a compliant model would also have a ModelWriter, which puts all the required dependencies into the
.mar zip file.  The Model Archive Format library, however, provides functionality that makes that requirement simple
to fulfill.


## MAR File Details

A .mar file is a ZIP file that contains the model itself (serialized bytes), all its code dependencies, and code for a
model reader that is capable of loading the model. Having all these components in a zip format allows the .mar file
to be a self-contained artifact and makes it easy to port it to other applications.

+ Serialized Model Bytes – the byte representation of the actual model (forms, encodings, etc., are *not* mandated)
+ Model Code
   * The class that defines the model, which implements the 'Model' trait from the scoring interfaces.  See the
     [scoring.interfaces.Model](https://github.com/trustedanalytics/ModelArchiver/blob/master/model-archive-interfaces/src/main/scala/org/trustedanalytics/scoring/interfaces/Model.scala)
   * The class that implements the 'ModelReader' trait from the scoring interfaces. The reader can use any
     dependencies in the .mar file to load the Model.  See the [scoring.interfaces.ModelReader](https://github.com/trustedanalytics/ModelArchiver/blob/master/model-archive-interfaces/src/main/scala/org/trustedanalytics/scoring/interfaces/ModelReader.scala)
   * Code Dependencies
+ Other Artifacts – there are *no* restrictions regarding what else can be put inside a MAR file

