from django.contrib import admin
from django.urls import path, include
from rest_framework.routers import DefaultRouter
from legal_aid_api.views import LegalAidRequestViewSet

router = DefaultRouter()
router.register(r'legal-aid-requests', LegalAidRequestViewSet)

urlpatterns = [
    path('admin/', admin.site.urls),
    path('api/', include(router.urls)),
] 