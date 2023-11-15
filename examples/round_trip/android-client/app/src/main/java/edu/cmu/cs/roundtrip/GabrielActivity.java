package edu.cmu.cs.roundtrip;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioRecord;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.function.Consumer;

import edu.cmu.cs.gabriel.camera.CameraCapture;
import edu.cmu.cs.gabriel.camera.YuvToJPEGConverter;
import edu.cmu.cs.gabriel.camera.ImageViewUpdater;
import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.gabriel.protocol.Protos;
import edu.cmu.cs.gabriel.protocol.Protos.InputFrame;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;
import edu.cmu.cs.roundtrip.protos.ClientExtras;

public class GabrielActivity extends AppCompatActivity {
    private static final String TAG = "GabrielActivity";
    private static final String SOURCE = "roundtrip";
    private static final int PORT = 8099;
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private ServerComm serverComm;
    private YuvToJPEGConverter yuvToJPEGConverter;
    private CameraCapture cameraCapture;

    private PreviewView previewView = null;
    private TextView textView = null;

    private EditText editText = null;

    private Button button = null;

    private String typed_string = "";

    private ByteString annotation_frame = null;

    private Instant annotation_display_start = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_gabriel);

        previewView = findViewById(R.id.preview);
        textView = findViewById(R.id.textAnnotation);
        editText = findViewById(R.id.editText);
        button = findViewById(R.id.startAnnotationButton);

        Consumer<ResultWrapper> consumer = resultWrapper -> {
            if (resultWrapper.getResultsCount() == 0) {
                return;
            }
            ResultWrapper.Result result = resultWrapper.getResults(0);
            ByteString byteString = result.getPayload();
            textView.setText(byteString.toStringUtf8());
            annotation_display_start = Instant.now();
        };

        Consumer<ErrorType> onDisconnect = errorType -> {
            Log.e(TAG, "Disconnect Error:" + errorType.name());
            finish();
        };

        serverComm = ServerComm.createServerComm(
                consumer, BuildConfig.GABRIEL_HOST, PORT, getApplication(), onDisconnect);

        yuvToJPEGConverter = new YuvToJPEGConverter(this);
        cameraCapture = new CameraCapture(this, analyzer, WIDTH, HEIGHT, previewView);

        // Set a click listener on the button
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                serverComm.sendSupplier(() -> {
//                    Bitmap bitmap = previewView.getBitmap();
//                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
//                    bitmap.compress(Bitmap.CompressFormat.JPEG,80,stream);
//                    return InputFrame.newBuilder().setPayloadType(PayloadType.IMAGE)
//                            .addPayloads(ByteString.copyFrom(stream.toByteArray()))
//                            .build();
//                }, SOURCE, false);
                typed_string = editText.getText().toString();
                editText.getText().clear();
            }
        });
    }

    final private ImageAnalysis.Analyzer analyzer = new ImageAnalysis.Analyzer() {
        @Override
        public void analyze(@NonNull ImageProxy image) {
            serverComm.sendSupplier(() -> {
                ByteString jpegByteString = yuvToJPEGConverter.convert(image);

                Protos.InputFrame.Builder builder = InputFrame.newBuilder()
                        .setPayloadType(PayloadType.IMAGE)
                        .addPayloads(jpegByteString);

                if (!typed_string.isEmpty()) {
                    ClientExtras.AnnotationData annotationData =
                            ClientExtras.AnnotationData.newBuilder()
                                    .setAnnotationText(typed_string)
                                    .setFrameData(jpegByteString)
                                    .build();
                    Any any = Any.newBuilder()
                            .setValue(annotationData.toByteString())
                            .setTypeUrl("type.googleapis.com/client.AnnotationData")
                            .build();
                    builder.setExtras(any);
                    typed_string = "";
                }
                return builder.build();
            }, SOURCE, false);

            image.close();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraCapture.shutdown();
    }
}
