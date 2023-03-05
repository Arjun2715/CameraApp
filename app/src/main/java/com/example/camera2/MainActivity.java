package com.example.camera2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


    public class MainActivity extends AppCompatActivity {
        private static final int GALLERY_REQUEST_CODE = 123;
        private static final String TAG = "AndroidCameraApi";
        private ImageButton takePictureButton;
        private ImageButton btnGalleryView;
        private TextureView textureView;
        private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
        static {
            ORIENTATIONS.append(Surface.ROTATION_0, 90);
            ORIENTATIONS.append(Surface.ROTATION_90, 0);
            ORIENTATIONS.append(Surface.ROTATION_180, 270);
            ORIENTATIONS.append(Surface.ROTATION_270, 180);
        }
        private String cameraId;
        protected CameraDevice cameraDevice;
        protected CameraCaptureSession cameraCaptureSessions;
        protected CaptureRequest captureRequest;
        protected CaptureRequest.Builder captureRequestBuilder;
        private Size imageDimension;
        private ImageReader imageReader;
        private File file;
        private static final int REQUEST_CAMERA_PERMISSION = 200;
        private boolean mFlashSupported;
        private Handler mBackgroundHandler;
        private HandlerThread mBackgroundThread;
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            btnGalleryView =(ImageButton) findViewById(R.id.btnGalleryView);

            btnGalleryView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(Intent.ACTION_QUICK_VIEW, Uri.parse("content:/storage/emulated/0/DCIM/Camera"));
                    startActivity(intent);
                }
            });





            //vinculamos con la textureView
            textureView = (TextureView) findViewById(R.id.texture);
            assert textureView != null;
            textureView.setSurfaceTextureListener(textureListener);
            takePictureButton = (ImageButton) findViewById(R.id.btn_takepicture);
            assert takePictureButton != null;
            //listener del boton para tomar la foto
            takePictureButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    takePicture();
                }
            });
        }

        TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                //open your camera here
                openCamera();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // Transform you image captured size according to the surface width and height
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        };
        private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                //This is called when the camera is open
                Log.e(TAG, "onOpened");
                cameraDevice = camera;
                createCameraPreview();
            }
            @Override
            public void onDisconnected(CameraDevice camera) {
                cameraDevice.close();
            }
            @Override
            public void onError(CameraDevice camera, int error) {
                cameraDevice.close();
                cameraDevice = null;
            }
        };
        final CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                createCameraPreview();
            }
        };
        protected void startBackgroundThread() {
            //crear el hilo secundario para ejecutar el código de abrir la cámara
            mBackgroundThread = new HandlerThread("Camera Background");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
        protected void stopBackgroundThread() {
            mBackgroundThread.quitSafely();
            try {
                //interrumpir el hilo secundario cuando se pause la cámara
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        protected void takePicture() {
            if(null == cameraDevice) {
                Log.e(TAG, "cameraDevice is null");
                return;
            }
            //Referenciamos el gestor de la camara
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            try {
                //sacamos las caracteristicas desde el gestor
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
                Size[] jpegSizes = null;
                if (characteristics != null) {
                    //sacamos el tamaño jpeg disponible
                    jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                }
                //valores por si no se ha podido obtener de las caracteristicas de la camara el ancho y el alto de la imagen
                int width = 640;
                int height = 480;
                if (jpegSizes != null && 0 < jpegSizes.length) {
                    //si se puede obtener les ponemos el valor que nos da la camara
                    width = jpegSizes[0].getWidth();
                    height = jpegSizes[0].getHeight();
                }
                //creamos una instancia de ImageReader que nos permite guardar un flujo de imagenes de la camara
                //tenemos que indicar tamaño, formato y que cantidad de imagenes (1)
                ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                //Crearemos una ArrayList de SurfaceTexture que nos permitira previsualizar la imagen
                List<Surface> outputSurfaces = new ArrayList<Surface>(2);
                //A partir del ImageReader podemos crear una instancia del Surface (preview de video)
                //outputSurfaces.add(reader.getSurface());
                //O podemos crear una instancia a partir de la textureView (que tenemos en el layout)
                outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
                //Creamos el captureBuilder que nos permite hacer peticiones de captura de vídeo
                //El parametro TEMPLATE_STILL_CAPTURE hace que sea una preview para capturar fotos (+calidad, -frame rate)
                final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                //Añadimos el ImageReader como el objetivo de la CaptureRequest
                captureBuilder.addTarget(reader.getSurface());
                //Configuramos los parametros de la camara como automaticos
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                // obtenemos que Orientacion tiene
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                //Configuramos para que tenga la orientacion correcta en el CaptureRequest
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
                //Ruta al archivo de destino
                final File file = new File(Environment.getExternalStorageDirectory()+"/pic.jpg");
                //Listener del ImageReader para cuando tenga imagen
                ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        Image image = null;
                        try {
                            //obtenemos la ultima imagen
                            image = reader.acquireLatestImage();
                            //creamos un buffer de imagenes
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            //creamos un array para guardar la informacion
                            byte[] bytes = new byte[buffer.capacity()];
                            //transmitimos los datos del buffer al array
                            buffer.get(bytes);
                            save(bytes);
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            if (image != null) {
                                image.close();
                            }
                        }
                    }
                    private void save(byte[] bytes) throws IOException {
                        OutputStream output = null;
                        try {
                            output = new FileOutputStream(file);
                            output.write(bytes);
                        } finally {
                            if (null != output) {
                                output.close();
                            }
                        }
                    }
                };
                //poner el listener activo y con el handler que envie los mensajes
                reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
                final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                        //cuando se ha completado la captura y hay todos los datos
                        super.onCaptureCompleted(session, request, result);
                        Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                        //metodo para poner la preview
                        createCameraPreview();
                    }
                };
                cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        try {
                            //realizar la petición de captura una vez la camara este configurada
                            session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                    }
                }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        @Override
        protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == GALLERY_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
                Uri imageUri = data.getData();
                // Do something with the selected image...
            }
        }
        protected void createCameraPreview() {
            try {
                //guardar la SurfaceTexture que es el buffer para la preview
                SurfaceTexture texture = textureView.getSurfaceTexture();
                assert texture != null;
                //fijar el tamaño de imagen a esperar
                texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
                //Creamos un objeto Surface a partir del SurfaceTexture
                Surface surface = new Surface(texture);
                //pide a la camara tomar una captura para la Surface
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                captureRequestBuilder.addTarget(surface);
                cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        //The camera is already closed
                        if (null == cameraDevice) {
                            return;
                        }
                        // When the session is ready, we start displaying the preview.
                        cameraCaptureSessions = cameraCaptureSession;
                        updatePreview();
                    }
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                    }
                }, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        private void openCamera() {
            //Referenciamos el gestor de la camara
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Log.e(TAG, "is camera open");
            try {
                //Nos quedaremos con la primera camara de la lista
                cameraId = manager.getCameraIdList()[0];
                //Podemos obtener que caracteristicas tiene la camara
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                //Mas en concreto podemos quedarnos con las caracteristicas de stream (calidad, formatos, duracion del frame, etc)
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                assert map != null;
                //De todas esas caracteristicas lo que mas nos importa es el tamaño de imagen
                imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

                // Add permission for camera and let user grant the permission
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]
                            {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                    return;
                }
                //Aqui abrimos la camara y establecemos la conexion.
                // con la clase stateCallback podemos gestionar que pasa cuando cambie de estado (cuando se abra, cuando se cierre, cuando se grabe
                manager.openCamera(cameraId, stateCallback, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            Log.e(TAG, "openCamera X");
        }
        protected void updatePreview() {
            if(null == cameraDevice) {
                Log.e(TAG, "updatePreview error, return");
            }
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            try {
                cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        private void closeCamera() {
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
        }
        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode == REQUEST_CAMERA_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    // close the app
                    Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }
        @Override
        protected void onResume() {
            super.onResume();
            Log.e(TAG, "onResume");
            startBackgroundThread();
            if (textureView.isAvailable()) {
                openCamera();
            } else {
                textureView.setSurfaceTextureListener(textureListener);
            }
        }
        @Override
        protected void onPause() {
            Log.e(TAG, "onPause");
            //closeCamera();
            stopBackgroundThread();
            super.onPause();
        }
    }