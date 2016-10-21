# model-archive-format

Model ARchive (MAR) is a format for storing and exchanging model information between various modeling tools and a Modeling Service. A .mar file contains the model, all its dependencies and a model adpater that is capable of loading the model. Having all these components in a zip format allows the .mar file to be a self-contained artifact and makes it easy to port it to other applications.


A MAR is a zip file that contains the following:
 - Model which implements the trait 'Model' from the scoring interfaces. This allows models from different modeling tools and having varied APIs to be enclosed inside a common wrapper and exposed using consistent APIs.
         Link to the scoring.interfaces.Model: https://github.com/trustedanalytics/ModelArchiver/blob/master/model-archive-interfaces/src/main/scala/org/trustedanalytics/scoring/interfaces/Model.scala
 - All the dependencies to load the model and score on it.
 - Model Adapter which implements the trait 'ModelReader' from the scoring interfaces. The adapter is capable of using the dependencies in the .mar file to load the Model.
         Link to the scoring.interfaces.ModelReader: https://github.com/trustedanalytics/ModelArchiver/blob/master/model-archive-interfaces/src/main/scala/org/trustedanalytics/scoring/interfaces/ModelReader.scala

