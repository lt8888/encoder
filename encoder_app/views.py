import json
import threading
import time

import serial  # 导入模块
import serial.tools.list_ports
from django.http import HttpResponse
from django.shortcuts import render
from dwebsocket.decorators import accept_websocket

# Create your views here.
# 设置ser_1 ser_2全局变量，默认值为serial对象
ser_1 = serial.Serial()
STRGLO = ""  # 读取的数据
BOOL = True  # 读取标志位

def home(req):
    return render(req, 'home.html')


# 扫描串口
@accept_websocket
def port_number(request):
    if not request.is_websocket():  # 判断是不是websocket连接
        try:  # 如果是普通的http方法
            message = request.GET['message']
            return HttpResponse(message)
        except:
            return render(request, 'login.html')
    else:
        plist = list(serial.tools.list_ports.comports())
        if len(plist) <= 0:
            print("没有发现端口!")
        else:
            port = []
            for PLIST in list(plist):
                port.append(PLIST[0])
            request.websocket.send(json.dumps(port))


# 打开串口
def open_serial_1(req):
    state = {"message": None}
    global ser_1
    try:
        com1 = req.POST.get("COM1")
        if ser_1.is_open:
            # 已经打开
            state["message"] = 0
        else:
            ser_1 = serial.Serial(com1, 115200, timeout=None)
            state["message"] = 1   # 打开成功
    except Exception as e:
        return e
    data = json.dumps(state)
    return HttpResponse(data)


# 关闭串口
def close_serial_1(req):
    state = {"message": None}
    global ser_1
    try:
        if ser_1.is_open:
            # 已经打开
            ser_1.close()  # 关闭成功
            state["message"] = 0
        else:

            state["message"] = 1   # 串口未打开
    except Exception as e:
        return e
    data = json.dumps(state)
    return HttpResponse(data)


# 开始采集
@accept_websocket
def gather(request):
    if not request.is_websocket():  # 判断是不是websocket连接
        try:  # 如果是普通的http方法
            message = request.GET['message']
            return HttpResponse(message)
        except:
            return render(request, 'home.html')
    else:
        for message in request.websocket:
            print(type(message))
            data_s1 = message.decode('utf-8')
            # 转换为数组request.websocket.send(message)发送消息到客户端
            data_s2 = data_s1.split(',')
            if data_s2[0] == '1':
                # 双通道连续
                print()
                data = '3A01102000050102000000C70D0A'
                DOpenPort(request, data)
            elif data_s2[0] == '2':
                # 双通道定次
                number = hex(int(data_s2[1]))
                head = "3A0110200005010201"
                trail = "0D0A"
                body = str(hex(int(data_s2[1])))[2:]
                lrc = []
                if len(body) == 3:
                    body = "0"+body
                elif len(body) == 2:
                    body = "00" + body
                else:
                    body = "000" + body
                print(body)
                lrc.append(int(body[0:2], 16))
                lrc.append(int(body[2:], 16))
                print(lrc)
                lrc_lt = str(hex((0xFF - (0x01 + 0x10 + 0x20 + 0x00 + 0x05 +0x01+0x02+0x01+
                                          lrc[0]+lrc[1]) + 1) & 0xFF))[2:]
                print(lrc_lt)
                data = head+body+lrc_lt+trail
                print(data)
                DOpenPort(request, data)
                lrc.clear()
            elif data_s2[0] == '3':
                # 单通道连续
                data = '3A01102000050101000000C80D0A'
                DOpenPort(request, data)
            else:
                # 单通道定次
                # 双通道定次
                number = hex(int(data_s2[1]))
                head = "3A0110200005010101"
                trail = "0D0A"
                body = str(hex(int(data_s2[1])))[2:]
                lrc = []
                if len(body) == 3:
                    body = "0"+body
                elif len(body) == 2:
                    body = "00" + body
                else:
                    body = "000" + body
                print(body)
                lrc.append(int(body[0:2], 16))
                lrc.append(int(body[2:], 16))
                print(lrc)
                lrc_lt = str(hex((0xFF - (0x01 + 0x10 + 0x20 + 0x00 + 0x05 +0x01+0x01+0x01+
                                          lrc[0]+lrc[1]) + 1) & 0xFF))[2:]
                print(lrc_lt)
                data = head+body+lrc_lt+trail
                print(data)
                DOpenPort(request, data)
                lrc.clear()


# 写数据实现
def WriteData(request, ser1, data):
    try:
        print(type(data))
        print("xie数据", data,)
        data_s1 = bytes.fromhex(data)
        print(data_s1)
        result = ser1.write(data_s1)  # 写数据
        print("写总字节数:", result)
    except Exception as e:
        print("异常--", e)


def ReadData(request, ser,):
    global STRGLO, BOOL
    BOOL = True
    # 循环接收数据，此为死循环，可用线程实现
    data = []
    lrc = []
    lrc2 = []
    b = 0
    data_message = []
    while BOOL:
        a = 0  # 结束while标志
        if ser.in_waiting:
            b = b+1
            if b>=7 and b<=10:
                STRGLO = ser.read(2).hex()  # .decode("gbk")
            else:
                STRGLO = ser.read(1).hex()  # .decode("gbk")
            FIRST = 0
            end = 0
            for str_glo in STRGLO:
                first = FIRST+end
                if b>=7 and b<=10:
                    end = first+4
                    data_message.append(int(STRGLO[first:end], 16))
                    lrc.append((STRGLO[first:end]))
                    if b == 10:
                        for lrc_s1 in lrc:
                            lrc2_s1 = lrc_s1[0:2]
                            lrc2_s2 = lrc_s1[2:4]
                            lrc2.append(int(lrc2_s1, 16))
                            lrc2.append(int(lrc2_s2, 16))
                        #request.websocket.send(json.dumps(data_message))
                else:
                    end = first + 2
                if b == 11:
                    lrc_lt = hex((0xFF - (0x01 + 0x03 + 0xA3 + 0x48 + 0x08 +
                                          lrc2[0] + lrc2[1] +lrc2[4] + lrc2[5] +lrc2[6] + lrc2[7] +
                                          lrc2[2] + lrc2[3]) + 1) & 0xFF)
                    print(lrc_lt, STRGLO[first:end])
                    if int(lrc_lt, 16) == int(STRGLO[first:end], 16):
                        print(data_message)
                        request.websocket.send(json.dumps(data_message))
                    else:
                        print("校验和不通过")
                data.append(int(STRGLO[first:end], 16))
                if(len(data) > 1):
                    if(data[len(data)-2]==13 and data[len(data)-1] == 10):
                        a = 1
                        # 初始化变量
                        b = 0
                        data_message.clear()
                        data.clear()
                        lrc2.clear()
                        lrc.clear()
                        print("数据传输结束")
                if end == len(STRGLO):
                    break


# 启动收发数据线程
def DOpenPort(request, data):
    global ser_1
    # portx 端口  bpx 波特率  timeout超时设置
    try:
        #判断是否打开成功
        if(ser_1.is_open):
            threading.Thread(target=ReadData, args=(request, ser_1,)).start()
            threading.Thread(target=WriteData, args=(request, ser_1, data)).start()
            print("ck", ser_1)
        else:
            # 串口未打开
            request.websocket.send(json.dumps(0))
    except Exception as e:
        print("---异常---：", e)
    return ser_1


# 停止采集
def stop(req):
    state = {"message": None}
    global BOOL, ser_1
    try:
        if BOOL:
            BOOL = False
            stop_lt = '3A01102000050000000000CA0D0A'
            if (ser_1.is_open):
                data_s1 = bytes.fromhex(stop_lt)
                print(data_s1)
                result = ser_1.write(data_s1)  # 写数据
            state["message"] = 1
        else:
            state["message"] = 0
    except Exception as e:
        print("stop异常", e)
    data = json.dumps(state)
    return HttpResponse(data)