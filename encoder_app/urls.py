from django.conf.urls import url
from . import views

urlpatterns = [
    url(r'^home', views.home),
    url(r'^port_number', views.port_number),
    url(r'^open_serial_1', views.open_serial_1),
    url(r'^close_serial_1', views.close_serial_1),
    url(r'^gather', views.gather),
    url(r'^stop', views.stop),
]