package com.hamed.enkeltchatapp;

import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    TextView connectionStatus, messageTextView;
    Button aSwitch, discoverButton;
    ListView listView;
    EditText typeMsg;
    ImageButton sendButton;

    WifiP2pManager manager;
    WifiP2pManager.Channel channel;

    BroadcastReceiver receiver;
    IntentFilter intentFilter;

    List<WifiP2pDevice> peers =  new ArrayList<WifiP2pDevice>();
    String[] deviceNameArr;
    WifiP2pDevice[] deviceArr;

    Socket socket;

    ServerClass serverClass;
    ClientClass clientClass;

    boolean isHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initialAll();
        exqListener();
    }

    //ExqListener-----------------------------------------------------------------------------------
    private void exqListener() {
        aSwitch.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            startActivityForResult(intent, 1);
        });

        discoverButton.setOnClickListener(v -> manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                connectionStatus.setText("Discovery Started");
            }

            @Override
            public void onFailure(int i) {
                connectionStatus.setText("Discovery Not Started");
            }
        }));

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final WifiP2pDevice device = deviceArr[position];
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = device.deviceAddress;
                manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        connectionStatus.setText("Connected: " + device.deviceName);
                    }

                    @Override
                    public void onFailure(int i) {
                        connectionStatus.setText("Not Connected");
                    }
                });
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                String msg = typeMsg.getText().toString();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (msg != null && isHost){
                            serverClass.write(msg.getBytes());
                        }else if (msg != null && !isHost){
                            clientClass.write(msg.getBytes());
                        }
                    }
                });
            }
        });
    }

    //InitialMethod---------------------------------------------------------------------------------
    private void initialAll() {
        connectionStatus = findViewById(R.id.connection_status);
        messageTextView = findViewById(R.id.messageTextView);
        aSwitch = findViewById(R.id.switch1);
        discoverButton = findViewById(R.id.buttonDiscover);
        listView = findViewById(R.id.listView);
        typeMsg = findViewById(R.id.editTextTypMsg);
        sendButton = findViewById(R.id.sendButton);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
    }

    WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList wifiP2pDeviceList) {
            if (!wifiP2pDeviceList.equals(peers)){
                peers.clear();
                peers.addAll(wifiP2pDeviceList.getDeviceList());

                deviceNameArr = new String[wifiP2pDeviceList.getDeviceList().size()];
                deviceArr = new WifiP2pDevice[wifiP2pDeviceList.getDeviceList().size()];

                int index = 0;
                for (WifiP2pDevice device : wifiP2pDeviceList.getDeviceList()){
                    deviceNameArr[index] = device.deviceName;
                    deviceArr[index] = device;
                    index++;
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(getApplicationContext(),
                        android.R.layout.simple_list_item_1, deviceNameArr);
                listView.setAdapter(adapter);

                if (peers.size() == 0){
                    connectionStatus.setText("No Device Found");
                    return;
                }
            }
        }
    };

    WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            final InetAddress groupOwnerAddress = wifiP2pInfo.groupOwnerAddress;
            if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner){
                connectionStatus.setText("Host");
                isHost = true;
                serverClass = new ServerClass();
                serverClass.start();
            }else if (wifiP2pInfo.groupFormed){
                connectionStatus.setText("Client");
                isHost = false;
                clientClass = new ClientClass(groupOwnerAddress);
                clientClass.start();
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    //Server----------------------------------------------------------------------------------------
    public class ServerClass extends Thread{
        ServerSocket serverSocket;
        private InputStream inputStream;
        private OutputStream outputStream;



        public void write(byte[] bytes){
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(8888);
                socket = serverSocket.accept();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Handler handler = new Handler(Looper.getMainLooper());

            executor.execute(() -> {
                byte[] buffer = new byte[1024];
                int bytes;

                while(socket != null){
                    try {
                        bytes = inputStream.read(buffer);
                        if (bytes > 0){
                            int finalBytes = bytes;
                            handler.post(() -> {
                                String tempMsg = new String(buffer, 0, finalBytes);
                                messageTextView.setText(tempMsg);
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    //Client----------------------------------------------------------------------------------------
    public class ClientClass extends Thread{
        String hostAdd;
        private InputStream inputStream;
        private OutputStream outputStream;

        public ClientClass(InetAddress hostAddress) {
            hostAdd = hostAddress.getHostAddress();
            socket = new Socket();
        }

        public void write(byte[] bytes){
            try {
                outputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                socket.connect(new InetSocketAddress(hostAdd, 8888), 500);
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Handler handler = new Handler(Looper.getMainLooper());

            executor.execute(() -> {
                byte[] buffer = new byte[1024];
                int bytes;

                while(socket != null){
                    try {
                        bytes = inputStream.read(buffer);
                        if (bytes > 0){
                            int finalBytes = bytes;
                            handler.post(() -> {
                                String tempMsg = new String(buffer, 0, finalBytes);
                                messageTextView.setText(tempMsg);
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }
}