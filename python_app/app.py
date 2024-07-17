import socket
import argparse
import datetime
import re
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading

serving_user = False

def hline():
    print('-'*200)

def setup_arg_parser():
    parser = argparse.ArgumentParser(description='Ngrok Received Data')
    parser.add_argument('--port', default=12345, type=int, help='Port Network')
    return parser.parse_args()

def read_int_from_file(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as file:
            return int(file.read().strip())
    except FileNotFoundError:
        return 0  # Assuming default count starts from 0 if file not found

def write_int_to_file(file_path, count):
    with open(file_path, 'w', encoding='utf-8') as file:
        file.write(str(count))

def accept_connections(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('', port))
        s.listen()
        print(f"Listening on port {port}...")
        while True:
            conn, addr = s.accept()
            with conn:
                print('Connected by', addr)
                handle_connection(conn)
                print(f"Listening on port {port}...")

def handle_connection(conn):
    global serving_user
    serving_user = True

    file_path = 'count.txt'
    count = read_int_from_file(file_path)
    try:
        while True:
            data = conn.recv(1024)
            if not data:
                hline()
                print("Connection lost or client disconnected.")  # Thông báo mất kết nối
                conn.sendall(b"connection loss")
                break
            process_data(data, count)
            count += 1
    except Exception as e:
        print(f"Error during connection: {e}")
        conn.sendall(b"connection loss")
    finally:
        write_int_to_file(file_path, count)
        serving_user = False
        print("Connection closed.")  # Thông báo đóng kết nối
        hline()


def process_data(data, count):
    time_recv = datetime.datetime.now()
    formatted_time = time_recv.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    data_string = f"Time Receive : {formatted_time} {data.decode()}"
    hline()
    print(f"Received Data: {data_string}")
    hline()
    extract_and_log_data(data_string, count)

def extract_and_log_data(data, count):
    matches = {
        'stt': re.search(r'STT: ([\d.]+)', data),
        'time_receive': re.search(r'Time Receive : ([\d\- :]+\.\d{3})', data),
        'id': re.search(r'ID : ([A-Za-z0-9]+(?:\s*-\s*[A-Za-z0-9]+)?)', data),
        'time_send': re.search(r'Time Send: ([\d\- :]+\.\d{3})', data),
        'latitude': re.search(r'Latitude: ([\d.]+)', data),
        'longitude': re.search(r'Longitude: ([\d.]+)', data),
        'speed': re.search(r'Speed: ([\d.]+)', data),
        'signal': re.search(r'Signal: ([\d.]+)', data)
    }
    
    for key, match in matches.items():
        if match:
            matches[key] = match.group(1)
        else:
            matches[key] = f"{key.replace('_', ' ')} not found"
    hline()
    print("Extracted Information")
    for info_key, info_value in matches.items():
        print(f"{info_key.capitalize()}: {info_value}")
    hline()
    log_data(count, **matches)

def log_data(count, **kwargs):
    with open("data.txt", "a") as file:
        file.write(f"{count},{kwargs['stt']},{kwargs['id']},{kwargs['signal']},{kwargs['time_send']},{kwargs['time_receive']},{kwargs['latitude']},{kwargs['longitude']},{kwargs['speed']},{calculate_latency(kwargs['time_send'], kwargs['time_receive'])}\n")

def calculate_latency(time_send, time_receive):
    time_format = "%Y-%m-%d %H:%M:%S.%f"
    time1 = datetime.datetime.strptime(time_send, time_format)
    time2 = datetime.datetime.strptime(time_receive, time_format)
    time_latency = (time2 - time1).total_seconds() * 1000
    return time_latency

class HealthCheckHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global serving_user
        if serving_user:
            self.send_response(503)
            self.end_headers()
        else:
            self.send_response(200)
            self.end_headers()

def start_health_check_server(port = 8000):
    health_check_server = HTTPServer(('', port), HealthCheckHandler)
    health_check_thread = threading.Thread(target=health_check_server.serve_forever)
    health_check_thread.daemon = True
    health_check_thread.start()

def main():
    args = setup_arg_parser()
    start_health_check_server(port=8000)
    accept_connections(args.port)

if __name__ == '__main__':
    main()

