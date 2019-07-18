import json
import threading
import serial  # 导入模块
import serial.tools.list_ports
from django.http import HttpResponse
from django.shortcuts import render
from dwebsocket.decorators import accept_websocket
# Create your views here.


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


